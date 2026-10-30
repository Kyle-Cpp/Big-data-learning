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
 * Evaluation job - walk-forward validation on the split=valid records,
 * stratified by tournament category (P1.2 of the improvement plan).
 *
 * For every validation match the model produces P(home win)/P(draw)/P(away
 * win) by integrating the DC-corrected score matrix (goals 0..maxGoals,
 * renormalised after truncation). Metrics per match:
 *
 *   RPS     ranked probability score (RpsMetrics), the standard metric
 *   logloss -ln P(assigned to the observed class), floored at 1e-12
 *   hit     argmax(P) equals the observed class
 *
 * Validation matches are scored with unit weight: time decay and the friendly
 * discount are training devices; forecast quality is measured per match.
 *
 * Emitted key: tournament category (major|qual|regional|friendly|other,
 * column 9 of the training-data record; legacy 8-column records fall back to
 * "all"). Emitted vector layout (see EvalReducer):
 *   [0] RPS  [1] logloss  [2] hits  [3] n
 *   [4] sum P(H)  [5] sum P(D)  [6] sum P(A)
 *   [7] n(H)  [8] n(D)  [9] n(A)
 */
public class EvalMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        VALID_MATCHES, BAD_LINE
    }

    public static final int OFF_RPS = 0;
    public static final int OFF_LOGLOSS = 1;
    public static final int OFF_HITS = 2;
    public static final int OFF_N = 3;
    public static final int OFF_SUM_PH = 4;
    public static final int OFF_SUM_PD = 5;
    public static final int OFF_SUM_PA = 6;
    public static final int OFF_NH = 7;
    public static final int OFF_ND = 8;
    public static final int OFF_NA = 9;
    public static final int DIM = 10;

    private DCModel model;
    private int maxGoals;
    private double[] local = new double[DIM];

    @Override
    protected void setup(Context context) throws IOException {
        String blob = context.getConfiguration().get("dc.model");
        if (blob == null) {
            throw new IOException("dc.model (Base64 parameter vector) missing in configuration");
        }
        model = DCModel.fromBase64(blob);
        maxGoals = context.getConfiguration().getInt("fp.eval.max.goals", 12);
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

        final double etaH = model.etaHome(homeIdx, awayIdx, neutral);
        final double etaA = model.etaAway(homeIdx, awayIdx);
        final double lambda = Math.exp(etaH);
        final double mu = Math.exp(etaA);

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
                double p = DCtau.tau(x, y, lambda, mu, model.rho) * pmfH[x] * pmfA[y];
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
        pW /= total;
        pD /= total;
        pA /= total;

        double rps = RpsMetrics.rps(pW, pD, pA, gh, ga);
        double pTrue = gh > ga ? pW : (gh == ga ? pD : pA);
        double logloss = -Math.log(Math.max(pTrue, 1e-12));
        double hit = (gh > ga && pW >= pD && pW >= pA)
                || (gh == ga && pD >= pW && pD >= pA)
                || (gh < ga && pA >= pW && pA >= pD) ? 1.0 : 0.0;

        local[OFF_RPS] += rps;
        local[OFF_LOGLOSS] += logloss;
        local[OFF_HITS] += hit;
        local[OFF_N] += 1.0;
        local[OFF_SUM_PH] += pW;
        local[OFF_SUM_PD] += pD;
        local[OFF_SUM_PA] += pA;
        if (gh > ga) {
            local[OFF_NH] += 1.0;
        } else if (gh == ga) {
            local[OFF_ND] += 1.0;
        } else {
            local[OFF_NA] += 1.0;
        }
        context.getCounter(Counters.VALID_MATCHES).increment(1);
        context.write(new Text(category), new VectorWritable(local));
        // reset the accumulation buffer for the next record (one emit per match)
        java.util.Arrays.fill(local, 0.0);
    }
}
