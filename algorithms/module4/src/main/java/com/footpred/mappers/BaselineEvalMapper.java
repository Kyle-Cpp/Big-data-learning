package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.DCModel;
import com.footpred.common.DCObjective;
import com.footpred.common.DCtau;
import com.footpred.common.RpsMetrics;
import com.footpred.common.VectorWritable;

/**
 * Baseline evaluation job (P1.1): scores the four-step information ladder on
 * the valid split, per tournament category:
 *
 *   B0  uniform 1/3,1/3,1/3                    - no information
 *   B1  train marginal H/D/A frequencies       - outcome structure only
 *       (by neutral/non-neutral venue)
 *   B2  constant-intensity independent Poisson - venue effect + goal rate,
 *       (no team parameters, no tau)             no team identity
 *   DC  the full Dixon-Coles model             - everything above + team
 *                                               attack/defence + tau
 *
 * All baseline parameters are computed by the driver from the train split
 * (leakage-free) and broadcast through the configuration; the DC model is
 * broadcast as dc.model (Base64).
 *
 * Emitted key: tournament category. Emitted vector (13):
 *   [0..2]  B0 rps/logloss/hit    [3..5]  B1 rps/logloss/hit
 *   [6..8]  B2 rps/logloss/hit    [9..11] DC rps/logloss/hit
 *   [12]    match count
 */
public class BaselineEvalMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        VALID_MATCHES, BAD_LINE
    }

    public static final int MODELS = 4; // B0, B1, B2, DC
    public static final int METRICS = 3; // rps, logloss, hit
    public static final int DIM = MODELS * METRICS + 1;
    public static final int OFF_COUNT = MODELS * METRICS;

    private DCModel model;
    private int maxGoals;
    // B1 marginal frequencies per venue group: [nnPw, nnPd, nnPa, cPw, cPd, cPa]
    private double[] b1;
    // B2 constant intensities per venue group: [nnLamH, nnLamA, cLamH, cLamA]
    private double[] b2;

    @Override
    protected void setup(Context context) throws IOException {
        String blob = context.getConfiguration().get("dc.model");
        if (blob == null) {
            throw new IOException("dc.model (Base64 parameter vector) missing in configuration");
        }
        model = DCModel.fromBase64(blob);
        maxGoals = context.getConfiguration().getInt("fp.eval.max.goals", 12);
        b1 = new double[]{
                context.getConfiguration().getDouble("baseline.b1.nn.pw", 1.0 / 3),
                context.getConfiguration().getDouble("baseline.b1.nn.pd", 1.0 / 3),
                context.getConfiguration().getDouble("baseline.b1.nn.pa", 1.0 / 3),
                context.getConfiguration().getDouble("baseline.b1.c.pw", 1.0 / 3),
                context.getConfiguration().getDouble("baseline.b1.c.pd", 1.0 / 3),
                context.getConfiguration().getDouble("baseline.b1.c.pa", 1.0 / 3),
        };
        b2 = new double[]{
                context.getConfiguration().getDouble("baseline.b2.nn.lamh", 1.4),
                context.getConfiguration().getDouble("baseline.b2.nn.lama", 1.1),
                context.getConfiguration().getDouble("baseline.b2.c.lamh", 1.2),
                context.getConfiguration().getDouble("baseline.b2.c.lama", 1.1),
        };
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String[] f = value.toString().split("\t");
        if (f.length < 8) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        if (!"valid".equals(f[7])) {
            return;
        }
        String category = f.length >= 9 ? f[8].trim() : "all";

        final int homeIdx = Integer.parseInt(f[1]);
        final int awayIdx = Integer.parseInt(f[2]);
        final int gh = Integer.parseInt(f[3]);
        final int ga = Integer.parseInt(f[4]);
        final boolean neutral = "1".equals(f[5]);

        // --- B0: uniform ---
        double[] pw0 = outcomeProbsUniform();

        // --- B1: train marginal frequencies by venue group ---
        double[] pw1 = neutral
                ? new double[]{b1[3], b1[4], b1[5]}
                : new double[]{b1[0], b1[1], b1[2]};

        // --- B2: constant independent Poisson by venue group ---
        double lamH = neutral ? b2[2] : b2[0];
        double lamA = neutral ? b2[3] : b2[1];
        double[] pw2 = outcomeProbsPoisson(Math.log(lamH), Math.log(lamA), 0.0);

        // --- DC: full model ---
        double etaH = model.etaHome(homeIdx, awayIdx, neutral);
        double etaA = model.etaAway(homeIdx, awayIdx);
        double[] pwDc = outcomeProbsPoisson(etaH, etaA, model.rho);

        double[][] models = {pw0, pw1, pw2, pwDc};
        double[] v = new double[DIM];
        for (int m = 0; m < MODELS; m++) {
            double pW = models[m][0];
            double pD = models[m][1];
            double pA = models[m][2];
            v[m * METRICS + 0] = RpsMetrics.rps(pW, pD, pA, gh, ga);
            double pTrue = gh > ga ? pW : (gh == ga ? pD : pA);
            v[m * METRICS + 1] = -Math.log(Math.max(pTrue, 1e-12));
            v[m * METRICS + 2] = (gh > ga && pW >= pD && pW >= pA)
                    || (gh == ga && pD >= pW && pD >= pA)
                    || (gh < ga && pA >= pW && pA >= pD) ? 1.0 : 0.0;
        }
        v[OFF_COUNT] = 1.0;
        context.getCounter(Counters.VALID_MATCHES).increment(1);
        context.write(new Text(category), new VectorWritable(v));
    }

    private static double[] outcomeProbsUniform() {
        return new double[]{1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0};
    }

    /**
     * W/D/A probabilities from Poisson intensities given as log-lambdas with
     * an optional tau correction (tau=0 for the B2 baseline). Matrix truncated
     * to 0..maxGoals and renormalised.
     */
    private double[] outcomeProbsPoisson(double etaH, double etaA, double rho) {
        double lambda = Math.exp(etaH);
        double mu = Math.exp(etaA);
        double[] pmfH = new double[maxGoals + 1];
        double[] pmfA = new double[maxGoals + 1];
        for (int x = 0; x <= maxGoals; x++) {
            pmfH[x] = Math.exp(x * etaH - lambda - DCObjective.lnFact(x));
            pmfA[x] = Math.exp(x * etaA - mu - DCObjective.lnFact(x));
        }
        double pW = 0.0;
        double pD = 0.0;
        double pA = 0.0;
        double total = 0.0;
        for (int x = 0; x <= maxGoals; x++) {
            for (int y = 0; y <= maxGoals; y++) {
                double p = DCtau.tau(x, y, lambda, mu, rho) * pmfH[x] * pmfA[y];
                total += p;
                if (x > y) {
                    pW += p;
                } else if (x == y) {
                    pD += p;
                } else {
                    pA += p;
                }
            }
        }
        return new double[]{pW / total, pD / total, pA / total};
    }
}
