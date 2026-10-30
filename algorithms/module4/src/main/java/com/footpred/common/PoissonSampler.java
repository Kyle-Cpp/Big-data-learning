package com.footpred.common;

import java.util.Random;

/**
 * Exact Poisson(lambda) sampling for the Monte-Carlo season simulation
 * (Job P5.2).
 *
 * Football-range intensities (lambda <= KNUTH_MAX_LAMBDA) use the classical
 * Knuth multiplication algorithm: draw uniforms until their product falls
 * below exp(-lambda); the loop count is an exact Poisson draw. For extreme
 * lambda (defensive only - the DC linear predictor is clipped at +-12, and
 * realistic lambdas stay well below 10) a rounded normal approximation
 * keeps the loop cost bounded.
 *
 * Sampled score lines are independent draws; the DC tau correction only
 * reshapes the joint low-score cells and is negligible for point
 * aggregation, so plain Poisson sampling is the standard replay choice.
 */
public final class PoissonSampler {

    /** Above this lambda the exact loop becomes expensive; switch to normal. */
    public static final double KNUTH_MAX_LAMBDA = 60.0;

    private PoissonSampler() {
    }

    /**
     * One draw from Poisson(lambda); fully determined by the given Random
     * state (replays with the same seed reproduce every sampled season).
     */
    public static int sample(Random rnd, double lambda) {
        if (lambda <= 0.0) {
            return 0;
        }
        if (lambda > KNUTH_MAX_LAMBDA) {
            return normalApproximation(rnd, lambda);
        }
        double limit = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    /** Box-Muller draw rounded to a non-negative integer; defensive branch. */
    private static int normalApproximation(Random rnd, double lambda) {
        double u1 = rnd.nextDouble();
        double u2 = rnd.nextDouble();
        double z = Math.sqrt(-2.0 * Math.log(Math.max(u1, 1e-12)))
                * Math.cos(2.0 * Math.PI * u2);
        double draw = lambda + Math.sqrt(lambda) * z;
        return draw <= 0.0 ? 0 : (int) Math.round(draw);
    }
}
