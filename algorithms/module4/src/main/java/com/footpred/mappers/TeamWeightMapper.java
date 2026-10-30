package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.TimeDecay;
import com.footpred.common.VectorWritable;

/**
 * Per-team effective sample size job (P3.2 of the improvement plan): sums the
 * time-decayed, friendly-discounted observation weight each team accumulated
 * in the train split. Directly diagnoses which teams rely on thin evidence
 * and therefore on the shrinkage prior (and which top-table entries are
 * trustworthy).
 *
 * Emitted: (teamIdx, VectorWritable[w]) for both participants of each match.
 */
public class TeamWeightMapper extends Mapper<LongWritable, Text, IntWritable, VectorWritable> {

    public enum Counters {
        TRAIN_MATCHES, BAD_LINE
    }

    private double xi;
    private double friendlyWeight;
    private long refEpochDay;

    @Override
    protected void setup(Context context) {
        xi = context.getConfiguration().getDouble("dc.xi", TimeDecay.xiFromHalfLifeDays(730));
        friendlyWeight = context.getConfiguration().getDouble("dc.friendly.weight", 0.4);
        refEpochDay = context.getConfiguration().getLong("fp.ref.epochday", 0L);
        if (refEpochDay == 0L) {
            throw new IllegalStateException("fp.ref.epochday missing in configuration");
        }
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String[] f = value.toString().split("\t");
        if (f.length < 8) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        if (!"train".equals(f[7])) {
            return;
        }
        long epochDay = Long.parseLong(f[0]);
        int homeIdx = Integer.parseInt(f[1]);
        int awayIdx = Integer.parseInt(f[2]);
        boolean friendly = "1".equals(f[6]);

        double w = TimeDecay.weight(xi, refEpochDay, epochDay);
        if (friendly) {
            w *= friendlyWeight;
        }
        VectorWritable out = new VectorWritable(new double[]{w});
        context.getCounter(Counters.TRAIN_MATCHES).increment(1);
        context.write(new IntWritable(homeIdx), out);
        context.write(new IntWritable(awayIdx), new VectorWritable(new double[]{w}));
    }
}
