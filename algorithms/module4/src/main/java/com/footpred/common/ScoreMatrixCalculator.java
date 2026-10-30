package com.footpred.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Computes one fixture's Dixon-Coles score distribution without Hadoop state. */
public final class ScoreMatrixCalculator {
    private ScoreMatrixCalculator() {
    }

    /** Returns the existing prediction TSV value, without the match key. */
    public static String calculate(double lambda, double mu, double rho, int maxGoals) {
        double[][] p = new double[maxGoals + 1][maxGoals + 1];
        double[] awayPmf = new double[maxGoals + 1];
        double lnLambda = Math.log(lambda);
        double lnMu = Math.log(mu);
        for (int y = 0; y <= maxGoals; y++) {
            awayPmf[y] = Math.exp(y * lnMu - mu - DCObjective.lnFact(y));
        }

        double total = 0.0;
        for (int x = 0; x <= maxGoals; x++) {
            double pmfH = Math.exp(x * lnLambda - lambda - DCObjective.lnFact(x));
            for (int y = 0; y <= maxGoals; y++) {
                double cell = DCtau.tau(x, y, lambda, mu, rho) * pmfH * awayPmf[y];
                p[x][y] = cell;
                total += cell;
            }
        }

        double pW = 0.0;
        double pD = 0.0;
        double pA = 0.0;
        List<double[]> cells = new ArrayList<double[]>((maxGoals + 1) * (maxGoals + 1));
        for (int x = 0; x <= maxGoals; x++) {
            for (int y = 0; y <= maxGoals; y++) {
                p[x][y] /= total;
                if (p[x][y] < 1e-9) {
                    continue;
                }
                cells.add(new double[]{x, y, p[x][y]});
                if (x > y) {
                    pW += p[x][y];
                } else if (x == y) {
                    pD += p[x][y];
                } else {
                    pA += p[x][y];
                }
            }
        }
        Collections.sort(cells, new Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(b[2], a[2]);
            }
        });
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < cells.size() && i < 5; i++) {
            double[] c = cells.get(i);
            if (i > 0) {
                top.append('|');
            }
            top.append((int) c[0]).append('-').append((int) c[1]).append(':')
                    .append(String.format(Locale.ROOT, "%.4f", c[2]));
        }
        return String.format(Locale.ROOT, "%.4f\t%.4f\t%.4f\t%.3f\t%.3f\t%s",
                pW, pD, pA, lambda, mu, top.toString());
    }
}
