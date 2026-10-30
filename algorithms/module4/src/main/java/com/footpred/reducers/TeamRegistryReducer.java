package com.footpred.reducers;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 * Job P0a - TeamRegistry reducer.
 *
 * Exactly one reduce task must be configured: keys arrive in lexicographic
 * order and the running counter becomes the stable team index used by every
 * later job. Output line format (TSV):
 *
 *     name <TAB> idx <TAB> matchCount
 */
public class TeamRegistryReducer extends Reducer<Text, LongWritable, Text, Text> {

    public enum Counters {
        N_TEAMS
    }

    private int nextIndex = 0;

    @Override
    protected void reduce(Text key, Iterable<LongWritable> values, Context context)
            throws IOException, InterruptedException {
        long total = 0;
        for (LongWritable v : values) {
            total += v.get();
        }
        context.write(key, new Text(nextIndex + "\t" + total));
        context.getCounter(Counters.N_TEAMS).increment(1);
        nextIndex++;
    }
}
