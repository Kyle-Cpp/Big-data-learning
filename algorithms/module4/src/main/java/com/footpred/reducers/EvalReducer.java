package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.EvalMapper;

/**
 * Evaluation job reducer (with VectorSumCombiner as combiner). One reduce
 * task aggregates the per-category sums and emits one report line per
 * (category, metrics) plus an "all" line accumulated across categories, so
 * the previous single-line output remains available.
 *
 *   category=major <TAB> matches=.. meanRPS=.. meanLogLoss=.. accuracy=..
 *     avgPredHome/Draw/Away=.. trueHome/Draw/Away=..
 */
public class EvalReducer extends Reducer<Text, VectorWritable, Text, Text> {

    private final Map<String, double[]> categorySums = new TreeMap<String, double[]>();

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] m = VectorSumCombiner.sum(values);
        if (m == null || m.length < EvalMapper.DIM) {
            return;
        }
        String category = key.toString();
        categorySums.put(category, m);
        if (m[EvalMapper.OFF_N] == 0.0) {
            return; // defensive: category with no matches
        }
        context.write(new Text("category=" + category), new Text(formatMetrics(m)));
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        double[] all = new double[EvalMapper.DIM];
        int nCategories = 0;
        for (double[] m : categorySums.values()) {
            for (int i = 0; i < all.length; i++) {
                all[i] += m[i];
            }
            nCategories++;
        }
        if (nCategories > 1 && all[EvalMapper.OFF_N] > 0.0) {
            context.write(new Text("category=all"), new Text(formatMetrics(all)));
        }
    }

    private static String formatMetrics(double[] m) {
        double n = m[EvalMapper.OFF_N];
        return "matches=" + (long) n
                + "\tmeanRPS=" + String.format(Locale.ROOT, "%.6f", m[EvalMapper.OFF_RPS] / n)
                + "\tmeanLogLoss=" + String.format(Locale.ROOT, "%.6f", m[EvalMapper.OFF_LOGLOSS] / n)
                + "\taccuracy=" + String.format(Locale.ROOT, "%.6f", m[EvalMapper.OFF_HITS] / n)
                + "\tavgPredHome=" + String.format(Locale.ROOT, "%.4f", m[EvalMapper.OFF_SUM_PH] / n)
                + "\tavgPredDraw=" + String.format(Locale.ROOT, "%.4f", m[EvalMapper.OFF_SUM_PD] / n)
                + "\tavgPredAway=" + String.format(Locale.ROOT, "%.4f", m[EvalMapper.OFF_SUM_PA] / n)
                + "\ttrueHome=" + (long) m[EvalMapper.OFF_NH]
                + "\ttrueDraw=" + (long) m[EvalMapper.OFF_ND]
                + "\ttrueAway=" + (long) m[EvalMapper.OFF_NA];
    }
}
