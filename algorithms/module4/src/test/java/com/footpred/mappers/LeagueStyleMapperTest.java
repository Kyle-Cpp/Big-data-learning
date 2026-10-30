package com.footpred.mappers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** P6.6 row parser: metric-independent validity handling. */
public class LeagueStyleMapperTest {

    /**
     * Builds a 22-column row. {@code technical} carries the 14 columns from
     * hthg through ar (hthg htag hs as hst ast hf af hc ac hy ay hr ar).
     */
    private static String row(String technical) {
        return "Premier League\t2000-01\t2000-08-19\tCharlton\tMan City\t4\t0\t"
                + technical + "\tM Dean";
    }

    @Test
    public void fullRowCountsEveryMetric() {
        double[] v = LeagueStyleMapper.parseRow(
                row("2\t0\t17\t8\t14\t4\t13\t12\t6\t6\t1\t2\t0\t0"));
        assertEquals(17, v.length);
        assertEquals(1.0, v[LeagueStyleMapper.OFF_N], 0.0);
        assertEquals(4.0, v[LeagueStyleMapper.OFF_GOALS], 0.0);
        assertEquals(2.0, v[LeagueStyleMapper.OFF_HALF], 0.0);
        assertEquals(1.0, v[LeagueStyleMapper.OFF_HALF_N], 0.0);
        assertEquals(25.0, v[LeagueStyleMapper.OFF_SHOTS], 0.0);
        assertEquals(18.0, v[LeagueStyleMapper.OFF_SOT], 0.0);
        assertEquals(25.0, v[LeagueStyleMapper.OFF_FOULS], 0.0);
        assertEquals(12.0, v[LeagueStyleMapper.OFF_CORNERS], 0.0);
        assertEquals(3.0, v[LeagueStyleMapper.OFF_YELLOWS], 0.0);
        assertEquals(0.0, v[LeagueStyleMapper.OFF_REDS], 0.0);
        assertEquals(1.0, v[LeagueStyleMapper.OFF_REDS_N], 0.0);
    }

    @Test
    public void emptyTechnicalColumnsSkipOnlyThoseMetrics() {
        // Ligue 1 2005/06 shape: shots present, fouls/corners empty
        double[] v = LeagueStyleMapper.parseRow(
                row("0\t0\t12\t9\t5\t3\t\t\t\t\t2\t3\t0\t0"));
        assertEquals(1.0, v[LeagueStyleMapper.OFF_N], 0.0);
        assertEquals(21.0, v[LeagueStyleMapper.OFF_SHOTS], 0.0);
        assertEquals(1.0, v[LeagueStyleMapper.OFF_SHOTS_N], 0.0);
        assertEquals(0.0, v[LeagueStyleMapper.OFF_FOULS], 0.0);
        assertEquals(0.0, v[LeagueStyleMapper.OFF_FOULS_N], 0.0);
        assertEquals(0.0, v[LeagueStyleMapper.OFF_CORNERS_N], 0.0);
        assertEquals(5.0, v[LeagueStyleMapper.OFF_YELLOWS], 0.0);
    }

    @Test
    public void emptyHalfTimeScoresStillCountFinalMetrics() {
        double[] v = LeagueStyleMapper.parseRow(
                row("\t\t17\t8\t14\t4\t13\t12\t6\t6\t1\t2\t0\t0"));
        assertEquals(1.0, v[LeagueStyleMapper.OFF_N], 0.0);
        assertEquals(4.0, v[LeagueStyleMapper.OFF_GOALS], 0.0);
        assertEquals(0.0, v[LeagueStyleMapper.OFF_HALF_N], 0.0);
        assertEquals(25.0, v[LeagueStyleMapper.OFF_SHOTS], 0.0);
    }

    @Test
    public void headerAndMalformedRowsReturnNull() {
        assertNull(LeagueStyleMapper.parseRow(""));
        assertNull(LeagueStyleMapper.parseRow("league\tseason\tdate"));
        assertNull(LeagueStyleMapper.parseRow(
                "Premier League\t2000-01\tnot-a-date\tA\tB\t1\t0\t1\t0\t"
                        + "1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\tR"));
        assertNull(LeagueStyleMapper.parseRow(
                "Premier League\t2000-01\t2000-08-19\tA\tB\tx\t0\t1\t0\t"
                        + "1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\t1\tR"));
    }

    @Test
    public void intOrNullToleratesEmptyAndGarbage() {
        assertEquals(Integer.valueOf(17), LeagueStyleMapper.intOrNull("17"));
        assertNull(LeagueStyleMapper.intOrNull(""));
        assertNull(LeagueStyleMapper.intOrNull(" "));
        assertNull(LeagueStyleMapper.intOrNull("3.5"));
    }
}
