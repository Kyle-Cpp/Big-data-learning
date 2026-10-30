package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.MessiEventsMapper;

/**
 * Job P6.5b reducer: one row per club season of the target player.
 *
 * Output: league \t season \t apps \t shots \t goals \t xG \t assists \t penGoals
 */
public class MessiEventsReducer extends Reducer<Text, VectorWritable, NullWritable, Text> {

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] t = VectorSumCombiner.sum(values);
        if (t == null || t.length < MessiEventsMapper.DIM) {
            return;
        }
        String row = key.toString()
                + "\t" + (long) t[MessiEventsMapper.OFF_APPS]
                + "\t" + (long) t[MessiEventsMapper.OFF_SHOTS]
                + "\t" + (long) t[MessiEventsMapper.OFF_GOALS]
                + "\t" + String.format(Locale.ROOT, "%.2f", t[MessiEventsMapper.OFF_XG])
                + "\t" + (long) t[MessiEventsMapper.OFF_ASSISTS]
                + "\t" + (long) t[MessiEventsMapper.OFF_PEN_GOALS];
        context.write(NullWritable.get(), new Text(row));
    }
}
