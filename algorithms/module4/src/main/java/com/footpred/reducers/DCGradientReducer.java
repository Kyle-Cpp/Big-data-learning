package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.DCMapper;

/**
 * Job P1 - final reducer: one reduce task aggregates every mapper's partial
 * vector into a single record read back by the driver after each iteration.
 *
 * Output line:
 *
 *   LL=<e> <TAB> WSUM=<f> <TAB> COUNT=<n> <TAB> GRAD=<g0> <g1> ... <gD-1>
 *
 * The gradient is that of the log-likelihood (to be maximised); the driver
 * negates it for its NLL minimisation loop.
 */
public class DCGradientReducer
        extends Reducer<IntWritable, VectorWritable, NullWritable, Text> {

    @Override
    protected void reduce(IntWritable key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] total = VectorSumCombiner.sum(values);
        if (total == null || total.length <= DCMapper.OFF_GRAD) {
            return;
        }
        StringBuilder sb = new StringBuilder(16 * total.length);
        sb.append("LL=").append(String.format(Locale.ROOT, "%.10e", total[DCMapper.OFF_LL]));
        sb.append("\tWSUM=").append(String.format(Locale.ROOT, "%.6f", total[DCMapper.OFF_WSUM]));
        sb.append("\tCOUNT=").append((long) total[DCMapper.OFF_COUNT]);
        sb.append("\tGRAD=");
        for (int i = DCMapper.OFF_GRAD; i < total.length; i++) {
            if (i > DCMapper.OFF_GRAD) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%.10e", total[i]));
        }
        context.write(NullWritable.get(), new Text(sb.toString()));
    }
}
