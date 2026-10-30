package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.VectorWritable;

/**
 * Baseline statistics job (P1.1 of the improvement plan): aggregates the
 * TRAIN split into outcome counts and goal sums, per neutral/non-neutral
 * venue. The driver turns these into the B1 (marginal frequencies) and B2
 * (constant-intensity Poisson) baseline parameters. Using the train split
 * only keeps the baselines leakage-free.
 *
 * Emitted vector layout (12):
 *   [0..2] non-neutral outcome counts H/D/A  [3..5] neutral outcome counts
 *   [6] sum home goals (non-neutral)  [7] sum away goals  [8] n (non-neutral)
 *   [9] sum home goals (neutral)     [10] sum away goals  [11] n (neutral)
 */
public class BaselineStatsMapper extends Mapper<LongWritable, Text, IntWritable, VectorWritable> {

    public enum Counters {
        TRAIN_MATCHES, BAD_LINE
    }

    public static final int OFF_NNH = 0;
    public static final int OFF_NND = 1;
    public static final int OFF_NNA = 2;
    public static final int OFF_NCH = 3;
    public static final int OFF_NCD = 4;
    public static final int OFF_NCA = 5;
    public static final int OFF_GHNN = 6;
    public static final int OFF_GANN = 7;
    public static final int OFF_NNN = 8;
    public static final int OFF_GHC = 9;
    public static final int OFF_GAC = 10;
    public static final int OFF_NC = 11;
    public static final int DIM = 12;

    private final IntWritable outKey = new IntWritable(0);

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
        final int gh = Integer.parseInt(f[3]);
        final int ga = Integer.parseInt(f[4]);
        final boolean neutral = "1".equals(f[5]);

        double[] v = new double[DIM];
        if (neutral) {
            v[gh > ga ? OFF_NCH : (gh == ga ? OFF_NCD : OFF_NCA)] += 1.0;
            v[OFF_GHC] += gh;
            v[OFF_GAC] += ga;
            v[OFF_NC] += 1.0;
        } else {
            v[gh > ga ? OFF_NNH : (gh == ga ? OFF_NND : OFF_NNA)] += 1.0;
            v[OFF_GHNN] += gh;
            v[OFF_GANN] += ga;
            v[OFF_NNN] += 1.0;
        }
        context.getCounter(Counters.TRAIN_MATCHES).increment(1);
        context.write(outKey, new VectorWritable(v));
    }
}
