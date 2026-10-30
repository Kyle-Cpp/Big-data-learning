package com.footpred.mappers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P6.7 helpers: scoreline bucket clamping and decade labels. */
public class ScoreDiffMapperTest {

    @Test
    public void bucketClampsToSignedRange() {
        assertEquals(-5, ScoreDiffMapper.bucketOf(-13));
        assertEquals(-5, ScoreDiffMapper.bucketOf(-5));
        assertEquals(-1, ScoreDiffMapper.bucketOf(-1));
        assertEquals(0, ScoreDiffMapper.bucketOf(0));
        assertEquals(3, ScoreDiffMapper.bucketOf(3));
        assertEquals(5, ScoreDiffMapper.bucketOf(5));
        assertEquals(5, ScoreDiffMapper.bucketOf(9));
    }

    @Test
    public void decadeLabelIsFloorTimesTenPlusS() {
        assertEquals("1870s", ScoreDiffMapper.decadeOf(1872));
        assertEquals("1990s", ScoreDiffMapper.decadeOf(1998));
        assertEquals("2010s", ScoreDiffMapper.decadeOf(2014));
        assertEquals("2020s", ScoreDiffMapper.decadeOf(2026));
    }
}
