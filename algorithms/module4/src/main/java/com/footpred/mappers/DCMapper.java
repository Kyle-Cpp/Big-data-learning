package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.DCModel;
import com.footpred.common.DCObjective;
import com.footpred.common.TimeDecay;
import com.footpred.common.VectorWritable;

/**
 * Job P1 - Dixon-Coles weighted log-likelihood gradient, map side.
 *
 * Input: one training record of Job P0b per line
 *
 *     epochDay <TAB> homeIdx <TAB> awayIdx <TAB> gh <TAB> ga
 *           <TAB> neutral <TAB> friendly <TAB> split
 *
 * Only records with split=train are consumed. The current parameter vector is
 * broadcast through the job configuration (dc.model, Base64) so every mapper
 * evaluates the identical theta; local gradient sums are emitted once per map
 * task in cleanup(). The per-match mathematics lives in
 * {@link DCObjective} and is unit-tested against numerical differentiation.
 *
 * Emitted vector layout:
 *   [0] weighted log-likelihood  [1] sum of weights  [2] match count
 *   [3 .. 3+D-1] gradient of the log-likelihood w.r.t. the flat parameter
 *   vector [attack(0..N-1), defense(0..N-1), homeAdv, rho].
 * The driver negates it to obtain the minimisation (NLL) gradient.
 */
public class DCMapper extends Mapper<LongWritable, Text, IntWritable, VectorWritable> {

    /** Layout constants shared with the reducer and the driver. */
    public static final int OFF_LL = 0;
    public static final int OFF_WSUM = 1;
    public static final int OFF_COUNT = 2;
    public static final int OFF_GRAD = 3;

    public enum Counters {
        TRAIN_MATCHES, BAD_LINE, BAD_TAU
    }

    private DCModel model;
    private double xi;
    private double friendlyWeight;
    private double[] local;
    private int gradDim;

    private final IntWritable outKey = new IntWritable(0);

    @Override
    protected void setup(Context context) throws IOException {
        String blob = context.getConfiguration().get("dc.model");
        if (blob == null) {
            throw new IOException("dc.model (Base64 parameter vector) missing in configuration");
        }
        model = DCModel.fromBase64(blob);
        xi = context.getConfiguration().getDouble("dc.xi", TimeDecay.xiFromHalfLifeDays(730));
        friendlyWeight = context.getConfiguration().getDouble("dc.friendly.weight", 0.4);
        gradDim = DCModel.flatDim(model.nTeams);
        local = new double[OFF_GRAD + gradDim];
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString();
        String[] f = line.split("\t");
        if (f.length < 8) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        if (!"train".equals(f[7])) {
            return; // validation records are reserved for the evaluation job
        }
        final long epochDay = Long.parseLong(f[0]);
        final int homeIdx = Integer.parseInt(f[1]);
        final int awayIdx = Integer.parseInt(f[2]);
        final int gh = Integer.parseInt(f[3]);
        final int ga = Integer.parseInt(f[4]);
        final boolean neutral = "1".equals(f[5]);
        final boolean friendly = "1".equals(f[6]);

        final double etaH = model.etaHome(homeIdx, awayIdx, neutral);
        final double etaA = model.etaAway(homeIdx, awayIdx);

        double w = TimeDecay.weight(xi, model.refEpochDay, epochDay);
        if (friendly) {
            w *= friendlyWeight;
        }

        DCObjective.Contribution c =
                DCObjective.evaluate(gh, ga, etaH, etaA, model.rho, w);
        if (c.tauClipped) {
            context.getCounter(Counters.BAD_TAU).increment(1);
        }

        // chain rule into the flat parameter vector:
        // etaH = attack[home] + defense[away] + homeAdv*(1-neutral)
        // etaA = attack[away] + defense[home]
        local[OFF_GRAD + homeIdx] += c.dEtaHome;
        local[OFF_GRAD + awayIdx] += c.dEtaAway;
        local[OFF_GRAD + model.nTeams + awayIdx] += c.dEtaHome;
        local[OFF_GRAD + model.nTeams + homeIdx] += c.dEtaAway;
        if (!neutral) {
            local[OFF_GRAD + 2 * model.nTeams] += c.dEtaHome;
        }
        local[OFF_GRAD + 2 * model.nTeams + 1] += c.dRho;

        local[OFF_LL] += c.ll;
        local[OFF_WSUM] += w;
        local[OFF_COUNT] += 1.0;
        context.getCounter(Counters.TRAIN_MATCHES).increment(1);
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        context.write(outKey, new VectorWritable(local));
    }

    /** Dimension of the emitted vector for a given number of teams. */
    public static int vectorDim(int nTeams) {
        return OFF_GRAD + DCModel.flatDim(nTeams);
    }
}
