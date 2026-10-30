package com.footpred.optim;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * L-BFGS on a strictly convex quadratic f(x) = 0.5 x'Ax - b'x: the iteration
 * driven exactly like the training driver (direction from history, unit step,
 * curvature pairs from accepted points) must converge to the minimiser.
 */
public class LBFGSTest {

    private static final class Quadratic {
        final double[][] a;
        final double[] b;

        Quadratic(double[][] a, double[] b) {
            this.a = a;
            this.b = b;
        }

        double[] grad(double[] x) {
            double[] g = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                double ax = 0.0;
                for (int j = 0; j < x.length; j++) {
                    ax += a[i][j] * x[j];
                }
                g[i] = ax - b[i];
            }
            return g;
        }

        double value(double[] x) {
            double xax = 0.0;
            double bx = 0.0;
            for (int i = 0; i < x.length; i++) {
                bx += b[i] * x[i];
                for (int j = 0; j < x.length; j++) {
                    xax += x[i] * a[i][j] * x[j];
                }
            }
            return 0.5 * xax - bx;
        }
    }

    @Test
    public void minimisesStrictlyConvexQuadratic() {
        double[][] a = {
                {4.0, 1.0, 0.0, 0.0},
                {1.0, 3.0, 0.5, 0.0},
                {0.0, 0.5, 2.5, 0.4},
                {0.0, 0.0, 0.4, 1.8},
        };
        double[] xStar = {1.0, -2.0, 0.5, 3.0};
        // b = A x* so the minimiser is exactly xStar
        double[] b = new double[4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                b[i] += a[i][j] * xStar[j];
            }
        }
        Quadratic q = new Quadratic(a, b);

        double[] x = new double[4]; // start at origin
        LBFGS lbfgs = new LBFGS(8);
        double[] prevX = null;
        double[] prevG = null;
        double step = 1e-3; // first-step gradient descent length

        for (int k = 0; k < 200; k++) {
            double[] g = q.grad(x);
            if (k == 0) {
                prevX = x.clone();
                prevG = g;
                step = 1e-3;
            } else {
                lbfgs.update(x, prevX, g, prevG);
                step = 1.0;
                prevX = x.clone();
                prevG = g;
            }
            double[] d = lbfgs.hasHistory() ? lbfgs.computeDirection(g) : negate(g);
            double[] next = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                next[i] = x[i] + step * d[i];
            }
            x = next;
        }
        assertArrayEquals(xStar, x, 1e-6);
        assertEquals(q.value(xStar), q.value(x), 1e-9);
    }

    @Test
    public void directionIsDescentForFreshHistory() {
        LBFGS lbfgs = new LBFGS(5);
        double[] g = {1.0, -2.0, 0.5};
        double[] d = lbfgs.computeDirection(g);
        // d = -g without history
        assertArrayEquals(negate(g), d, 0.0);
    }

    @Test
    public void zeroCurvaturePairIsRejected() {
        LBFGS lbfgs = new LBFGS(5);
        double[] x0 = {0.0, 0.0};
        double[] x1 = {1.0, 1.0};
        double[] g0 = {0.0, 0.0}; // identical gradients -> y = 0 -> s.y = 0
        double[] g1 = {0.0, 0.0};
        assertFalse(lbfgs.update(x1, x0, g1, g0));
        assertFalse(lbfgs.hasHistory());
        assertEquals(0, lbfgs.historyDepth());
    }

    @Test
    public void resetClearsHistory() {
        LBFGS lbfgs = new LBFGS(5);
        // s = {1,1}, y = {1,1}: positive curvature, pair is stored
        double[] x0 = {0.0, 0.0};
        double[] x1 = {1.0, 1.0};
        double[] g0 = {2.0, 1.0};
        double[] g1 = {3.0, 2.0};
        assertTrue(lbfgs.update(x1, x0, g1, g0));
        assertTrue(lbfgs.hasHistory());
        lbfgs.reset();
        assertFalse(lbfgs.hasHistory());
    }

    @Test
    public void historyDepthIsBounded() {
        LBFGS lbfgs = new LBFGS(2);
        for (int k = 1; k <= 6; k++) {
            double[] x0 = {k - 1.0, 0.0};
            double[] x1 = {k * 1.0, 0.0};
            double[] g0 = {k * 2.0, 1.0};
            double[] g1 = {k * 2.0 + 1.0, 1.0};
            lbfgs.update(x1, x0, g1, g0);
        }
        assertEquals(2, lbfgs.historyDepth());
    }

    private static double[] negate(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = -v[i];
        }
        return out;
    }
}
