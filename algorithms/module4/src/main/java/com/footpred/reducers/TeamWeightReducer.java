package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.TeamRegistry;
import com.footpred.common.VectorWritable;

/**
 * Per-team effective sample size reducer (with VectorSumCombiner as
 * combiner). Output line (TSV):
 *
 *     teamName <TAB> effectiveWeightedMatches
 */
public class TeamWeightReducer extends Reducer<IntWritable, VectorWritable, Text, Text> {

    private TeamRegistry registry;

    @Override
    protected void setup(Context context) throws IOException {
        String registryPath = context.getConfiguration().get("fp.registry.path");
        if (registryPath != null) {
            registry = TeamRegistry.load(new org.apache.hadoop.fs.Path(registryPath),
                    context.getConfiguration());
        }
    }

    @Override
    protected void reduce(IntWritable key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] total = VectorSumCombiner.sum(values);
        if (total == null || total.length < 1) {
            return;
        }
        int idx = key.get();
        String name = registry != null ? registry.nameOf(idx) : null;
        if (name == null) {
            name = "team#" + idx;
        }
        context.write(new Text(name),
                new Text(String.format(Locale.ROOT, "%.2f", total[0])));
    }
}
