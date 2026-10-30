package com.footpred.reducers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** P6.8 diversity indices against hand-computed reference values. */
public class NationalityReducerTest {

    @Test
    public void singleCountryLeagueIsFullyConcentrated() {
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        counts.put("Spain", new int[]{20});
        double[] idx = NationalityReducer.diversityIndex(counts, "Spain");
        assertEquals(1.0, idx[0], 1e-12); // HHI
        assertEquals(0.0, idx[1], 1e-12); // Shannon
        assertEquals(0.0, idx[2], 1e-12); // foreign share
    }

    @Test
    public void evenSplitMatchesHandComputedValues() {
        // 8 Spain + 4 Brazil + 2 Argentina + 2 France = 16 total
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        counts.put("Spain", new int[]{8});
        counts.put("Brazil", new int[]{4});
        counts.put("Argentina", new int[]{2});
        counts.put("France", new int[]{2});
        double[] idx = NationalityReducer.diversityIndex(counts, "Spain");
        // shares p = [0.5, 0.25, 0.125, 0.125]
        double hhi = 0.5 * 0.5 + 0.25 * 0.25 + 2 * (0.125 * 0.125);
        double shannon = -(0.5 * Math.log(0.5) + 0.25 * Math.log(0.25)
                + 2 * (0.125 * Math.log(0.125)));
        assertEquals(hhi, idx[0], 1e-12);
        assertEquals(shannon, idx[1], 1e-12);
        assertEquals(0.5, idx[2], 1e-12);
    }

    @Test
    public void unknownHomeCountryYieldsNaNForeignShare() {
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        counts.put("Spain", new int[]{10});
        counts.put("Brazil", new int[]{10});
        double[] idx = NationalityReducer.diversityIndex(counts, null);
        assertEquals(0.5, idx[0], 1e-12);
        assertTrue(Double.isNaN(idx[2]));
    }

    @Test
    public void unknownCountryBucketCountsAsForeign() {
        // "Unknown" (missing country field) is not the home country
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        counts.put("England", new int[]{15});
        counts.put("Unknown", new int[]{5});
        double[] idx = NationalityReducer.diversityIndex(counts, "England");
        assertEquals(0.25, idx[2], 1e-12);
    }

    @Test
    public void homeCountryMapCoversTheFiveLeagues() {
        assertEquals("Spain", NationalityReducer.HOME_COUNTRY.get("La Liga"));
        assertEquals("England", NationalityReducer.HOME_COUNTRY.get("Premier League"));
        assertEquals("Italy", NationalityReducer.HOME_COUNTRY.get("Serie A"));
        assertEquals("Germany", NationalityReducer.HOME_COUNTRY.get("Bundesliga"));
        assertEquals("France", NationalityReducer.HOME_COUNTRY.get("Ligue 1"));
    }
}
