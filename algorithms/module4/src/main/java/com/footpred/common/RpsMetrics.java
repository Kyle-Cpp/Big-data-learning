package com.footpred.common;

/**
 * Ranked Probability Score for the home/draw/away ordered outcome - the
 * standard forecast-quality metric for football score-line probabilities.
 *
 *     RPS = 0.5 * [ (pW - oW)^2 + (pW + pD - oW - oD)^2 ]
 *
 * with oW/oD one-hot indicators of the observed outcome.
 */
public final class RpsMetrics {

    private RpsMetrics() {
    }

    public static double rps(double pW, double pD, double pA, int homeGoals, int awayGoals) {
        double oW = homeGoals > awayGoals ? 1.0 : 0.0;
        double oD = homeGoals == awayGoals ? 1.0 : 0.0;
        double d1 = pW - oW;
        double d2 = pW + pD - oW - oD;
        return 0.5 * (d1 * d1 + d2 * d2);
    }
}
