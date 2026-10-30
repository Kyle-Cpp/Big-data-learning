package com.footpred.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class PoissonSamplerTest {

    @Test
    public void zeroLambdaAlwaysYieldsZero() {
        Random rnd = new Random(1L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, PoissonSampler.sample(rnd, 0.0));
        }
    }

    @Test
    public void sameSeedReproducesSequence() {
        Random a = new Random(42L);
        Random b = new Random(42L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(PoissonSampler.sample(a, 1.8),
                    PoissonSampler.sample(b, 1.8));
        }
    }

    @Test
    public void meanAndVarianceMatchLambda() {
        double lambda = 1.8;
        int n = 200000;
        Random rnd = new Random(7L);
        double sum = 0.0;
        double sumSq = 0.0;
        for (int i = 0; i < n; i++) {
            int draw = PoissonSampler.sample(rnd, lambda);
            sum += draw;
            sumSq += (double) draw * draw;
        }
        double mean = sum / n;
        double variance = sumSq / n - mean * mean;
        assertEquals(lambda, mean, 0.02);
        assertEquals(lambda, variance, 0.15);
    }

    @Test
    public void largeLambdaNormalBranchKeepsMean() {
        double lambda = 64.0; // above KNUTH_MAX_LAMBDA -> normal approximation
        int n = 5000;
        Random rnd = new Random(11L);
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += PoissonSampler.sample(rnd, lambda);
        }
        assertEquals(lambda, sum / n, 1.5);
    }

    @Test
    public void drawsStayNonNegative() {
        Random rnd = new Random(13L);
        for (int i = 0; i < 10000; i++) {
            assertTrue(PoissonSampler.sample(rnd, 3.5) >= 0);
            assertTrue(PoissonSampler.sample(rnd, 64.0) >= 0);
        }
    }
}
