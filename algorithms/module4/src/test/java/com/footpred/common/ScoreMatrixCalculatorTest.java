package com.footpred.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScoreMatrixCalculatorTest {
    @Test
    public void zeroGoalTruncationKeepsPredictionFormatAndNormalizes() {
        assertEquals("0.0000\t1.0000\t0.0000\t1.200\t0.900\t0-0:1.0000",
                ScoreMatrixCalculator.calculate(1.2, 0.9, -0.06, 0));
    }
}
