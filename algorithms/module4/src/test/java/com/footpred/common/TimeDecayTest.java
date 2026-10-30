package com.footpred.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TimeDecayTest {

    @Test
    public void halfLifeConversion() {
        assertEquals(Math.log(2.0) / 730.0, TimeDecay.xiFromHalfLifeDays(730), 1e-15);
        assertEquals(Math.log(2.0) / 365.0, TimeDecay.xiFromHalfLifeDays(365), 1e-15);
    }

    @Test
    public void weightAtReferenceDateIsOne() {
        double xi = TimeDecay.xiFromHalfLifeDays(730);
        assertEquals(1.0, TimeDecay.weight(xi, 20000L, 20000L), 1e-15);
    }

    @Test
    public void weightHalvesAfterOneHalfLife() {
        double xi = TimeDecay.xiFromHalfLifeDays(730);
        assertEquals(0.5, TimeDecay.weight(xi, 20000L, 20000L - 730), 1e-12);
        assertEquals(0.25, TimeDecay.weight(xi, 20000L, 20000L - 1460), 1e-12);
    }

    @Test
    public void matchAfterReferenceGetsFullWeight() {
        double xi = TimeDecay.xiFromHalfLifeDays(730);
        assertEquals(1.0, TimeDecay.weight(xi, 20000L, 20010L), 1e-15);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveHalfLifeRejected() {
        TimeDecay.xiFromHalfLifeDays(0);
    }
}
