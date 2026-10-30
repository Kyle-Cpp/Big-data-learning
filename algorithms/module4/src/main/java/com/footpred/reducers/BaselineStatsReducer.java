package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.BaselineStatsMapper;

/**
 * Baseline statistics reducer: one reduce task emits a single key=value line
 * the driver parses to construct the B1/B2 baseline parameters.
 *
 *   NNH=.. NND=.. NNA=.. NCH=.. NCD=.. NCA=..
 *   GHNN=.. GANN=.. NNN=.. GHC=.. GAC=.. NC=..
 */
public class BaselineStatsReducer
        extends Reducer<IntWritable, VectorWritable, NullWritable, Text> {

    @Override
    protected void reduce(IntWritable key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] total = VectorSumCombiner.sum(values);
        if (total == null || total.length < BaselineStatsMapper.DIM) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        append(sb, "NNH", total[BaselineStatsMapper.OFF_NNH]);
        append(sb, "NND", total[BaselineStatsMapper.OFF_NND]);
        append(sb, "NNA", total[BaselineStatsMapper.OFF_NNA]);
        append(sb, "NCH", total[BaselineStatsMapper.OFF_NCH]);
        append(sb, "NCD", total[BaselineStatsMapper.OFF_NCD]);
        append(sb, "NCA", total[BaselineStatsMapper.OFF_NCA]);
        append(sb, "GHNN", total[BaselineStatsMapper.OFF_GHNN]);
        append(sb, "GANN", total[BaselineStatsMapper.OFF_GANN]);
        append(sb, "NNN", total[BaselineStatsMapper.OFF_NNN]);
        append(sb, "GHC", total[BaselineStatsMapper.OFF_GHC]);
        append(sb, "GAC", total[BaselineStatsMapper.OFF_GAC]);
        append(sb, "NC", total[BaselineStatsMapper.OFF_NC]);
        context.write(NullWritable.get(), new Text(sb.toString()));
    }

    private static void append(StringBuilder sb, String name, double value) {
        if (sb.length() > 0) {
            sb.append('\t');
        }
        sb.append(name).append('=').append(String.format(Locale.ROOT, "%.1f", value));
    }
}
