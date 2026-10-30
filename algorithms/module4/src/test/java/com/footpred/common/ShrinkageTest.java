package com.footpred.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Shrinkage prior: value and gradient cross-checked numerically. */
public class ShrinkageTest {

    private static final double EPS = 1e-7;

    @Test
    public void penaltyValueMatchesDefinition() {
        double[] attack = {0.5, -0.3};
        double[] defense = {0.1, 0.2};
        double sigma2 = 0.1;
        double expected = (0.5 * 0.5 + 0.3 * 0.3 + 0.1 * 0.1 + 0.2 * 0.2) / (2 * sigma2);
        assertEquals(expected, Shrinkage.penaltyValue(attack, defense, sigma2), 1e-12);
        // scales linearly in 1/sigma2
        assertEquals(expected * 2.0, Shrinkage.penaltyValue(attack, defense, sigma2 / 2), 1e-9);
    }

    @Test
    public void gradientMatchesNumericalDifferentiation() {
        double[] attack = {0.55, -0.25, 0.10};
        double[] defense = {-0.45, 0.05, 0.30};
        double sigma2 = 0.05;
        int n = attack.length;
        double[] gradFlat = new double[2 * n + 2];
        // data part of the flat gradient is irrelevant to the penalty; set zeros
        Shrinkage.addPenaltyGradient(gradFlat, attack, defense, sigma2);

        // numerical derivative of penaltyValue w.r.t. attack[i] / defense[i]
        for (int i = 0; i < n; i++) {
            double[] aPlus = attack.clone();
            double[] aMinus = attack.clone();
            aPlus[i] += EPS;
            aMinus[i] -= EPS;
            double numA = (Shrinkage.penaltyValue(aPlus, defense, sigma2)
                    - Shrinkage.penaltyValue(aMinus, defense, sigma2)) / (2 * EPS);
            assertEquals("attack[" + i + "]", numA, gradFlat[i], 1e-5);

            double[] dPlus = defense.clone();
            double[] dMinus = defense.clone();
            dPlus[i] += EPS;
            dMinus[i] -= EPS;
            double numD = (Shrinkage.penaltyValue(attack, dPlus, sigma2)
                    - Shrinkage.penaltyValue(attack, dMinus, sigma2)) / (2 * EPS);
            assertEquals("defense[" + i + "]", numD, gradFlat[n + i], 1e-5);
        }
        // homeAdv and rho stay unpenalised
        assertEquals(0.0, gradFlat[2 * n], 0.0);
        assertEquals(0.0, gradFlat[2 * n + 1], 0.0);
    }

    @Test
    public void zeroParametersHaveZeroPenaltyGradient() {
        double[] attack = {0.0, 0.0};
        double[] defense = {0.0, 0.0};
        double[] gradFlat = new double[6];
        Shrinkage.addPenaltyGradient(gradFlat, attack, defense, 0.2);
        for (double v : gradFlat) {
            assertEquals(0.0, v, 0.0);
        }
        assertEquals(0.0, Shrinkage.penaltyValue(attack, defense, 0.2), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveSigma2Rejected() {
        Shrinkage.penaltyValue(new double[]{1.0}, new double[]{1.0}, 0.0);
    }

    @Test
    public void priorInformationInterpretation() {
        // sigma2 = 0.1 -> prior worth ~10 effective matches per unit of pull:
        // a team parameter at 0.3 experiences gradient 0.3/0.1 = 3.0
        double[] attack = {0.3};
        double[] defense = {0.0};
        double[] gradFlat = new double[4];
        Shrinkage.addPenaltyGradient(gradFlat, attack, defense, 0.1);
        assertTrue(gradFlat[0] > 2.99 && gradFlat[0] < 3.01);
    }
}
