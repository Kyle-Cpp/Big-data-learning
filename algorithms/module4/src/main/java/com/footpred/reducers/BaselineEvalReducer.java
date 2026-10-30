package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.BaselineEvalMapper;

/**
 * Baseline evaluation reducer: one reduce task emits one line per
 * (category, model) of the information ladder plus "all" lines accumulated
 * across categories.
 *
 *   category=major <TAB> model=DC <TAB> matches=.. rps=.. logloss=.. accuracy=..
 */
public class BaselineEvalReducer extends Reducer<Text, VectorWritable, Text, Text> {

    private static final String[] MODEL_NAMES = {"B0-uniform", "B1-marginal", "B2-constpoisson", "DC-full"};

    private final Map<String, double[]> categorySums = new TreeMap<String, double[]>();

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] m = VectorSumCombiner.sum(values);
        if (m == null || m.length < BaselineEvalMapper.DIM) {
            return;
        }
        String category = key.toString();
        categorySums.put(category, m);
        if (m[BaselineEvalMapper.OFF_COUNT] > 0.0) {
            emitLadder(context, "category=" + category, m);
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (categorySums.size() > 1) {
            double[] all = new double[BaselineEvalMapper.DIM];
            for (double[] m : categorySums.values()) {
                for (int i = 0; i < all.length; i++) {
                    all[i] += m[i];
                }
            }
            if (all[BaselineEvalMapper.OFF_COUNT] > 0.0) {
                emitLadder(context, "category=all", all);
            }
        }
    }

    private void emitLadder(Context context, String prefix, double[] m) throws IOException,
            InterruptedException {
        double n = m[BaselineEvalMapper.OFF_COUNT];
        for (int model = 0; model < BaselineEvalMapper.MODELS; model++) {
            int rpsOff = model * BaselineEvalMapper.METRICS + 0;
            int llOff = model * BaselineEvalMapper.METRICS + 1;
            int hitOff = model * BaselineEvalMapper.METRICS + 2;
            String line = "model=" + MODEL_NAMES[model]
                    + "\tmatches=" + (long) n
                    + "\trps=" + String.format(Locale.ROOT, "%.6f", m[rpsOff] / n)
                    + "\tlogloss=" + String.format(Locale.ROOT, "%.6f", m[llOff] / n)
                    + "\taccuracy=" + String.format(Locale.ROOT, "%.6f", m[hitOff] / n);
            context.write(new Text(prefix), new Text(line));
        }
    }
}
