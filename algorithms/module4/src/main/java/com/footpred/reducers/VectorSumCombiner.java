package com.footpred.reducers;

import java.io.IOException;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;

/**
 * Generic vector-sum combiner used by the DC gradient job, the evaluation job
 * and the baseline jobs. Summation is associative and commutative, so the same
 * class safely serves as Combiner (map-side pre-aggregation) and as the
 * aggregation stage of a reducer chain. The key type is intentionally generic
 * so both IntWritable- and Text-keyed jobs reuse it.
 *
 * Values arriving through the iterable are consumed immediately (Hadoop reuses
 * the deserialisation instance between next() calls).
 */
public class VectorSumCombiner
        extends Reducer<WritableComparable<?>, VectorWritable, WritableComparable<?>, VectorWritable> {

    @Override
    protected void reduce(WritableComparable<?> key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] acc = null;
        for (VectorWritable v : values) {
            if (acc == null) {
                acc = new double[v.size()];
            }
            v.addInto(acc);
        }
        if (acc != null) {
            context.write(key, new VectorWritable(acc));
        }
    }

    /** Reusable helper for reducers that aggregate into a typed result. */
    public static double[] sum(Iterable<VectorWritable> values) {
        double[] acc = null;
        for (VectorWritable v : values) {
            if (acc == null) {
                acc = new double[v.size()];
            }
            v.addInto(acc);
        }
        return acc;
    }
}
