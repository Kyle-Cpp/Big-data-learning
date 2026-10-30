package com.footpred.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

/** Writable transport round-trip: the exact bytes shipped between MR stages. */
public class VectorWritableTest {

    @Test
    public void writeReadRoundTrip() throws IOException {
        double[] v = {1.5, -2.25e-9, 0.0, 12345.6789, Double.MIN_VALUE};
        VectorWritable writable = new VectorWritable(v);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        writable.write(out);
        out.flush();

        VectorWritable read = new VectorWritable();
        read.readFields(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        assertArrayEquals(v, read.get(), 0.0);
        assertEquals(v.length, read.size());
    }

    @Test
    public void emptyVectorRoundTrip() throws IOException {
        VectorWritable writable = new VectorWritable();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        writable.write(out);
        out.flush();

        VectorWritable read = new VectorWritable();
        read.readFields(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        assertEquals(0, read.size());
    }

    @Test
    public void addIntoAccumulatesElementWise() {
        VectorWritable a = new VectorWritable(new double[]{1.0, 2.0, 3.0});
        VectorWritable b = new VectorWritable(new double[]{0.5, -1.0, 4.0});
        VectorWritable c = new VectorWritable(new double[]{0.25, 0.25, 0.25});
        double[] acc = new double[3];
        a.addInto(acc);
        b.addInto(acc);
        c.addInto(acc);
        assertArrayEquals(new double[]{1.75, 1.25, 7.25}, acc, 1e-12);
    }

    @Test
    public void plusReturnsElementWiseSum() {
        VectorWritable a = new VectorWritable(new double[]{1.0, 2.0});
        VectorWritable b = new VectorWritable(new double[]{10.0, 20.0});
        assertArrayEquals(new double[]{11.0, 22.0}, a.plus(b).get(), 1e-12);
    }
}
