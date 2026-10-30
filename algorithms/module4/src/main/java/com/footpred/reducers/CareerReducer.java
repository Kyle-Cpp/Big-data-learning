package com.footpred.reducers;

import java.io.IOException;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.CareerCsvMapper;

/**
 * Job P6.5a reducer: one row per (player, source, season).
 *
 * Output: player \t source \t season \t apps \t goals \t pens \t ogs
 */
public class CareerReducer extends Reducer<Text, VectorWritable, NullWritable, Text> {

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] t = VectorSumCombiner.sum(values);
        if (t == null || t.length < CareerCsvMapper.DIM) {
            return;
        }
        StringBuilder sb = new StringBuilder(80);
        sb.append(key.toString());
        for (double x : t) {
            sb.append('\t').append((long) x);
        }
        context.write(NullWritable.get(), new Text(sb.toString()));
    }
}
