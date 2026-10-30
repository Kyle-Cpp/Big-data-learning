package com.footpred.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** RPS reference values computed by hand. */
public class RpsMetricsTest {

    @Test
    public void perfectForecastScoresZero() {
        assertEquals(0.0, RpsMetrics.rps(1.0, 0.0, 0.0, 2, 0), 0.0);
        assertEquals(0.0, RpsMetrics.rps(0.0, 1.0, 0.0, 1, 1), 0.0);
        assertEquals(0.0, RpsMetrics.rps(0.0, 0.0, 1.0, 0, 3), 0.0);
    }

    @Test
    public void uniformForecastOnHomeWin() {
        // p=(1/3,1/3,1/3), observed home win: RPS = 0.5*((1/3-1)^2+(2/3-1)^2)
        double rps = RpsMetrics.rps(1.0 / 3, 1.0 / 3, 1.0 / 3, 1, 0);
        assertEquals(0.5 * (4.0 / 9.0 + 1.0 / 9.0), rps, 1e-12); // = 5/18 ~ 0.2778
    }

    @Test
    public void uniformForecastOnDraw() {
        // observed draw: RPS = 0.5*((1/3-0)^2 + (2/3-1)^2) = 0.5*(1/9+1/9)
        double rps = RpsMetrics.rps(1.0 / 3, 1.0 / 3, 1.0 / 3, 2, 2);
        assertEquals(1.0 / 9.0, rps, 1e-12);
    }

    @Test
    public void confidentWrongForecastScoresWorseThanUniform() {
        double confidentWrong = RpsMetrics.rps(0.9, 0.05, 0.05, 0, 2); // predicted H, away won
        double uniform = RpsMetrics.rps(1.0 / 3, 1.0 / 3, 1.0 / 3, 0, 2);
        assertEquals(true, confidentWrong > uniform);
    }

    @Test
    public void orderedOutcomeMattersNotSymmetry() {
        // swapping H and A probabilities changes the score for a home win
        double a = RpsMetrics.rps(0.6, 0.2, 0.2, 2, 1);
        double b = RpsMetrics.rps(0.2, 0.2, 0.6, 2, 1);
        assertEquals(true, a < b); // correct ordering ranked closer
    }
}
