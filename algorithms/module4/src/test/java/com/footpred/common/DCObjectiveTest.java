package com.footpred.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The training objective itself: values and exact gradients verified against
 * central numerical differentiation on every tau branch and several
 * score combinations.
 */
public class DCObjectiveTest {

    private static final double EPS = 1e-6;

    private static double llAt(int gh, int ga, double etaH, double etaA, double rho, double w) {
        return DCObjective.evaluate(gh, ga, etaH, etaA, rho, w).ll;
    }

    @Test
    public void gradientsMatchNumericalDifferentiation() {
        int[][] scores = {{0, 0}, {0, 1}, {1, 0}, {1, 1}, {2, 1}, {3, 0}, {0, 2}, {4, 2}};
        double[] rhoCases = {-0.08, 0.0, 0.05};
        double[] wCases = {1.0, 0.4};
        for (int[] s : scores) {
            for (double rho : rhoCases) {
                for (double w : wCases) {
                    double etaH = 0.35;
                    double etaA = 0.10;
                    DCObjective.Contribution c =
                            DCObjective.evaluate(s[0], s[1], etaH, etaA, rho, w);
                    assertFalse(c.tauClipped);

                    double numEtaH = (llAt(s[0], s[1], etaH + EPS, etaA, rho, w)
                            - llAt(s[0], s[1], etaH - EPS, etaA, rho, w)) / (2 * EPS);
                    double numEtaA = (llAt(s[0], s[1], etaH, etaA + EPS, rho, w)
                            - llAt(s[0], s[1], etaH, etaA - EPS, rho, w)) / (2 * EPS);
                    double numRho = (llAt(s[0], s[1], etaH, etaA, rho + EPS, w)
                            - llAt(s[0], s[1], etaH, etaA, rho - EPS, w)) / (2 * EPS);

                    String tag = s[0] + "-" + s[1] + " rho=" + rho + " w=" + w;
                    assertEquals(tag + " dEtaHome", numEtaH, c.dEtaHome, 1e-4);
                    assertEquals(tag + " dEtaAway", numEtaA, c.dEtaAway, 1e-4);
                    assertEquals(tag + " dRho", numRho, c.dRho, 1e-4);
                }
            }
        }
    }

    @Test
    public void weightScalesValueAndGradientLinearly() {
        DCObjective.Contribution w1 = DCObjective.evaluate(1, 1, 0.3, 0.1, -0.05, 1.0);
        DCObjective.Contribution w2 = DCObjective.evaluate(1, 1, 0.3, 0.1, -0.05, 0.4);
        assertEquals(0.4 * w1.ll, w2.ll, 1e-12);
        assertEquals(0.4 * w1.dEtaHome, w2.dEtaHome, 1e-12);
        assertEquals(0.4 * w1.dEtaAway, w2.dEtaAway, 1e-12);
        assertEquals(0.4 * w1.dRho, w2.dRho, 1e-12);
    }

    @Test
    public void clippedTauReportsFlag() {
        // rho at its clip bound with large intensities: tau(0,0) floors
        DCObjective.Contribution c = DCObjective.evaluate(0, 0, 2.0, 2.0, 0.15, 1.0);
        assertTrue(c.tauClipped);
        assertTrue(Double.isFinite(c.ll));
    }

    @Test
    public void likelihoodDegradesWhenPredictionMovesAway() {
        // observed 3 goals: the likelihood peaks near lambda = 3 (etaH ~ 1.1)
        double low = llAt(3, 0, 0.0, 0.0, -0.05, 1.0);
        double mid = llAt(3, 0, 1.0, 0.0, -0.05, 1.0);
        double high = llAt(3, 0, 1.1, 0.0, -0.05, 1.0);
        double overshoot = llAt(3, 0, 5.0, 0.0, -0.05, 1.0);
        assertTrue("mid > low", mid > low);
        assertTrue("high > mid", high > mid);
        assertTrue("overshoot < high", overshoot < high);
    }

    @Test
    public void lnFactMatchesDirectComputation() {
        assertEquals(0.0, DCObjective.lnFact(0), 0.0);
        assertEquals(Math.log(1), DCObjective.lnFact(1), 1e-12);
        assertEquals(Math.log(2), DCObjective.lnFact(2), 1e-12);
        assertEquals(Math.log(6), DCObjective.lnFact(3), 1e-12);
        assertEquals(Math.log(120), DCObjective.lnFact(5), 1e-12);
        // beyond the pre-computed table
        double acc = 0.0;
        for (int i = 1; i <= 30; i++) {
            acc += Math.log(i);
        }
        assertEquals(acc, DCObjective.lnFact(30), 1e-9);
    }
}
