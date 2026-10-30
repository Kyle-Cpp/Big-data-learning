package com.footpred.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import org.apache.hadoop.io.Writable;

/**
 * Generic double-vector container used to ship partial gradient sums and
 * scalar auxiliaries between Mapper, Combiner and Reducer.
 *
 * The semantic layout of the vector is defined by the job that uses it
 * (see DCGradientJob / EvaluationJob constants). This class only handles
 * transport and summation.
 */
public class VectorWritable implements Writable {

    private double[] values;

    public VectorWritable() {
        this.values = new double[0];
    }

    public VectorWritable(int dim) {
        this.values = new double[dim];
    }

    public VectorWritable(double[] values) {
        this.values = values;
    }

    public double[] get() {
        return values;
    }

    public int size() {
        return values.length;
    }

    /** Accumulates this vector into {@code acc} element-wise. */
    public void addInto(double[] acc) {
        for (int i = 0; i < values.length; i++) {
            acc[i] += values[i];
        }
    }

    /** Element-wise sum of this vector with {@code other}, returning a new vector. */
    public VectorWritable plus(VectorWritable other) {
        double[] otherValues = other.get();
        if (otherValues.length != values.length) {
            throw new IllegalArgumentException(
                    "Vector length mismatch: " + values.length + " vs " + otherValues.length);
        }
        double[] out = Arrays.copyOf(values, values.length);
        for (int i = 0; i < out.length; i++) {
            out[i] += otherValues[i];
        }
        return new VectorWritable(out);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        out.writeInt(values.length);
        for (double v : values) {
            out.writeDouble(v);
        }
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        int len = in.readInt();
        double[] read = new double[len];
        for (int i = 0; i < len; i++) {
            read[i] = in.readDouble();
        }
        this.values = read;
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
