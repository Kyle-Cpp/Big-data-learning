package com.footpred.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** tau values and exact derivatives, cross-checked with central differences. */
public class DCtauTest {

    private static final double EPS = 1e-7;

    private static double numerical(double x, double y, double lambda, double mu,
                                    double rho, String var) {
        double d = EPS;
        if ("lambda".equals(var)) {
            return (DCtau.tau((int) x, (int) y, lambda + d, mu, rho)
                    - DCtau.tau((int) x, (int) y, lambda - d, mu, rho)) / (2 * d);
        }
        if ("mu".equals(var)) {
            return (DCtau.tau((int) x, (int) y, lambda, mu + d, rho)
                    - DCtau.tau((int) x, (int) y, lambda, mu - d, rho)) / (2 * d);
        }
        return (DCtau.tau((int) x, (int) y, lambda, mu, rho + d)
                - DCtau.tau((int) x, (int) y, lambda, mu, rho - d)) / (2 * d);
    }

    @Test
    public void tauValuesMatchPaperDefinition() {
        double lam = 1.4;
        double mu = 1.1;
        double rho = -0.08;
        assertEquals(1 - lam * mu * rho, DCtau.tau(0, 0, lam, mu, rho), 1e-12);
        assertEquals(1 + lam * rho, DCtau.tau(0, 1, lam, mu, rho), 1e-12);
        assertEquals(1 + mu * rho, DCtau.tau(1, 0, lam, mu, rho), 1e-12);
        assertEquals(1 - rho, DCtau.tau(1, 1, lam, mu, rho), 1e-12);
        assertEquals(1.0, DCtau.tau(2, 0, lam, mu, rho), 0.0);
        assertEquals(1.0, DCtau.tau(0, 3, lam, mu, rho), 0.0);
        assertEquals(1.0, DCtau.tau(3, 2, lam, mu, rho), 0.0);
    }

    @Test
    public void derivativesMatchNumericalDifferentiation() {
        double[][] cases = {
                {0, 0, 1.4, 1.1, -0.08},
                {0, 1, 1.4, 1.1, -0.08},
                {1, 0, 1.4, 1.1, -0.08},
                {1, 1, 1.4, 1.1, -0.08},
                {2, 1, 1.4, 1.1, -0.08},
                {0, 0, 0.4, 2.8, 0.06},
                {0, 1, 0.4, 2.8, 0.06},
                {1, 0, 0.4, 2.8, 0.06},
                {1, 1, 0.4, 2.8, 0.06},
        };
        for (double[] c : cases) {
            int x = (int) c[0];
            int y = (int) c[1];
            DCtau.TauResult r = DCtau.tauAndDerivatives(x, y, c[2], c[3], c[4]);
            assertFalse(r.clipped);
            String tag = "(" + x + "," + y + ")";
            assertEquals(tag + " dLambda", numerical(x, y, c[2], c[3], c[4], "lambda"),
                    r.dTauDlambda, 1e-6);
            assertEquals(tag + " dMu", numerical(x, y, c[2], c[3], c[4], "mu"),
                    r.dTauDmu, 1e-6);
            assertEquals(tag + " dRho", numerical(x, y, c[2], c[3], c[4], "rho"),
                    r.dTauDrho, 1e-6);
        }
    }

    @Test
    public void extremeRhoFloorsTauAndZeroesDerivatives() {
        // lambda*mu*rho > 1 pushes tau(0,0) negative: guard must engage
        DCtau.TauResult r = DCtau.tauAndDerivatives(0, 0, 5.0, 5.0, 0.15);
        assertTrue(r.clipped);
        assertEquals(DCtau.TAU_FLOOR, r.tau, 0.0);
        assertEquals(0.0, r.dTauDlambda, 0.0);
        assertEquals(0.0, r.dTauDmu, 0.0);
        assertEquals(0.0, r.dTauDrho, 0.0);
    }

    @Test
    public void tauOnlyMatchesFullComputation() {
        double lam = 1.3;
        double mu = 0.9;
        double rho = -0.1;
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 3; y++) {
                assertEquals(x + "-" + y,
                        DCtau.tauAndDerivatives(x, y, lam, mu, rho).tau,
                        DCtau.tau(x, y, lam, mu, rho), 0.0);
            }
        }
    }
}
