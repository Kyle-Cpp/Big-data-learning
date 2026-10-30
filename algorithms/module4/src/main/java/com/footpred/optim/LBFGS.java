package com.footpred.optim;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Standard limited-memory BFGS (two-loop recursion) for the driver-side
 * optimisation loop. The objective is a minimisation (negative weighted
 * log-likelihood); each iteration's gradient is produced by a MapReduce job
 * and the parameter update happens in the driver, which is the classic
 * "parameter broadcast + gradient reduction" pattern of distributed training.
 *
 * Curvature pairs (s, y) are kept up to {@code historySize} entries; a pair is
 * only stored when s.y exceeds a small positive threshold (curvature
 * condition surrogate), which keeps the recursion numerically safe when a
 * step is rejected and retried.
 */
public class LBFGS {

    public static final double CURVATURE_EPS = 1e-10;

    private final int historySize;
    private final Deque<double[]> sHistory = new ArrayDeque<double[]>();
    private final Deque<double[]> yHistory = new ArrayDeque<double[]>();
    private final Deque<Double> invRhoHistory = new ArrayDeque<Double>();

    public LBFGS(int historySize) {
        if (historySize < 1) {
            throw new IllegalArgumentException("historySize must be >= 1");
        }
        this.historySize = historySize;
    }

    public void reset() {
        sHistory.clear();
        yHistory.clear();
        invRhoHistory.clear();
    }

    public boolean hasHistory() {
        return !sHistory.isEmpty();
    }

    public int historyDepth() {
        return sHistory.size();
    }

    /**
     * Search direction d = -H_k * g for the minimisation objective, computed
     * with the two-loop recursion over the stored curvature pairs. Deque
     * head/tail correspond to oldest/newest pair; index order in the arrays
     * below is oldest..newest.
     */
    public double[] computeDirection(double[] g) {
        int dim = g.length;
        double[] q = new double[dim];
        System.arraycopy(g, 0, q, 0, dim);

        int depth = sHistory.size();
        double[][] sArr = sHistory.toArray(new double[0][]);
        double[][] yArr = yHistory.toArray(new double[0][]);
        Double[] invRhoArr = invRhoHistory.toArray(new Double[0]);
        double[] alpha = new double[depth];

        // first loop: newest to oldest, q -= alpha_i * y_i
        for (int i = depth - 1; i >= 0; i--) {
            alpha[i] = invRhoArr[i] * dot(sArr[i], q);
            axpy(q, yArr[i], -alpha[i]);
        }

        // initial scaling gamma = (s.y)/(y.y) of the newest pair
        double gamma = 1.0;
        if (depth > 0) {
            double[] sNewest = sArr[depth - 1];
            double[] yNewest = yArr[depth - 1];
            double yy = dot(yNewest, yNewest);
            if (yy > CURVATURE_EPS) {
                gamma = dot(sNewest, yNewest) / yy;
            }
        }
        scale(q, gamma);

        // second loop: oldest to newest, q += (alpha_i - beta_i) * s_i
        for (int i = 0; i < depth; i++) {
            double beta = invRhoArr[i] * dot(yArr[i], q);
            axpy(q, sArr[i], alpha[i] - beta);
        }

        // d = -q
        for (int i = 0; i < dim; i++) {
            q[i] = -q[i];
        }
        return q;
    }

    /**
     * Stores the curvature pair produced by one accepted step:
     * s = xNew - xOld, y = gNew - gOld. The pair is dropped (history
     * untouched) when the curvature condition s.y &lt;= eps fails.
     *
     * @return true when the pair was stored.
     */
    public boolean update(double[] xNew, double[] xOld, double[] gNew, double[] gOld) {
        int dim = xNew.length;
        double[] s = new double[dim];
        double[] y = new double[dim];
        for (int i = 0; i < dim; i++) {
            s[i] = xNew[i] - xOld[i];
            y[i] = gNew[i] - gOld[i];
        }
        double sy = dot(s, y);
        if (sy <= CURVATURE_EPS) {
            return false;
        }
        sHistory.addLast(s);
        yHistory.addLast(y);
        invRhoHistory.addLast(1.0 / sy);
        if (sHistory.size() > historySize) {
            sHistory.pollFirst();
            yHistory.pollFirst();
            invRhoHistory.pollFirst();
        }
        return true;
    }

    // ---------------- small dense-vector helpers ---------------- //

    static double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    static void axpy(double[] acc, double[] x, double a) {
        for (int i = 0; i < acc.length; i++) {
            acc[i] += a * x[i];
        }
    }

    static void scale(double[] v, double a) {
        for (int i = 0; i < v.length; i++) {
            v[i] *= a;
        }
    }
}
