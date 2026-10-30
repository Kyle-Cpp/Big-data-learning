package com.footpred.common;

/**
 * Gaussian (ridge) shrinkage prior on attack/defence parameters - the
 * penalised Dixon-Coles objective of Dixon &amp; Coles (1997), section 6.3:
 *
 *     F_p = F + (1/(2*sigma2)) * sum_j (attack_j^2 + defense_j^2)
 *
 * homeAdv and rho are global parameters with thousands of effective
 * observations and stay unpenalised. The prior information 1/sigma2 has a
 * direct interpretation as "a number of effective matches voting for the
 * league-average strength", so shrinkage is negligible for well-observed
 * teams and strong for teams with only a handful of (time-decayed)
 * observations.
 *
 * Applied driver-side after the gradient job returns; the MapReduce layer
 * stays untouched.
 */
public final class Shrinkage {

    private Shrinkage() {
    }

    /**
     * Penalty value (to be added to the NLL).
     *
     * @param sigma2 prior variance; must be positive
     */
    public static double penaltyValue(double[] attack, double[] defense, double sigma2) {
        checkSigma2(sigma2);
        double sum = 0.0;
        for (double a : attack) {
            sum += a * a;
        }
        for (double d : defense) {
            sum += d * d;
        }
        return sum / (2.0 * sigma2);
    }

    /**
     * Adds the penalty gradient in-place to a flat NLL gradient laid out as
     * [attack(0..n-1), defense(0..n-1), homeAdv, rho]:
     *
     *     dF_p/dattack_j = dF/dattack_j + attack_j / sigma2
     *     dF_p/ddefense_j = dF/ddefense_j + defense_j / sigma2
     */
    public static void addPenaltyGradient(double[] gradFlat, double[] attack,
                                         double[] defense, double sigma2) {
        checkSigma2(sigma2);
        int n = attack.length;
        if (gradFlat.length != 2 * n + 2) {
            throw new IllegalArgumentException(
                    "flat gradient length " + gradFlat.length + " != " + (2 * n + 2));
        }
        for (int i = 0; i < n; i++) {
            gradFlat[i] += attack[i] / sigma2;
            gradFlat[n + i] += defense[i] / sigma2;
        }
    }

    private static void checkSigma2(double sigma2) {
        if (!(sigma2 > 0.0)) {
            throw new IllegalArgumentException("sigma2 must be positive: " + sigma2);
        }
    }
}
