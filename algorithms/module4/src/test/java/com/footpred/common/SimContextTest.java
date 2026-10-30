package com.footpred.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SimContextTest {

    private static SimContext.League sampleLeague() {
        return new SimContext.League("Ligue 1", 2, 4,
                Arrays.asList("Paris SG", "Marseille", "Lyon"),
                new int[]{7, 4, 1},
                new int[]{6, 3, 1},
                new int[]{2, 3, 2},
                new int[]{3, 3, 2},
                new int[]{15, 10, 5},
                new int[]{1, 2, 3});
    }

    @Test
    public void encodeDecodeRoundTripPreservesLeagueState() throws IOException {
        List<SimContext.League> leagues = new ArrayList<SimContext.League>();
        leagues.add(sampleLeague());
        leagues.add(new SimContext.League("Bundesliga", 2, 4,
                Arrays.asList("Dortmund", "München"),
                new int[]{3, 6}, new int[]{4, 6}, new int[]{1, 2},
                new int[]{2, 2}, new int[]{9, 12}, new int[]{2, 1}));
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        fixtures.add(new SimContext.Fixture("Ligue 1", 0, 2,
                1.5234567890123456, 0.9876543210987654, 20712L));
        fixtures.add(new SimContext.Fixture("Bundesliga", 1, 0,
                1.25, 0.75, 20713L));

        SimContext decoded = SimContext.decode(new SimContext(leagues, fixtures).encode());

        assertEquals(2, decoded.leagues.size());
        SimContext.League lig = decoded.leagueByName("Ligue 1");
        assertNotNull(lig);
        assertEquals(2, lig.relegationPlaces);
        assertEquals(4, lig.topPlaces);
        assertEquals(Arrays.asList("Paris SG", "Marseille", "Lyon"), lig.teamNames);
        assertArrayEquals(new int[]{7, 4, 1}, lig.points);
        assertArrayEquals(new int[]{6, 3, 1}, lig.goalsFor);
        assertArrayEquals(new int[]{2, 3, 2}, lig.goalsAgainst);
        assertArrayEquals(new int[]{3, 3, 2}, lig.played);
        assertArrayEquals(new int[]{15, 10, 5}, lig.realPoints);
        assertArrayEquals(new int[]{1, 2, 3}, lig.realRank);
        SimContext.League bul = decoded.leagueByName("Bundesliga");
        assertArrayEquals(new int[]{9, 12}, bul.realPoints);
        assertArrayEquals(new int[]{2, 1}, bul.realRank);
    }

    @Test
    public void encodeDecodeRoundTripPreservesFixturesExactly() throws IOException {
        List<SimContext.League> leagues = new ArrayList<SimContext.League>();
        leagues.add(sampleLeague());
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        fixtures.add(new SimContext.Fixture("Ligue 1", 1, 2, 2.25, 0.5, 20690L));
        SimContext decoded = SimContext.decode(new SimContext(leagues, fixtures).encode());
        assertEquals(1, decoded.fixtures.size());
        SimContext.Fixture f = decoded.fixtures.get(0);
        assertEquals("Ligue 1", f.league);
        assertEquals(1, f.homeIdx);
        assertEquals(2, f.awayIdx);
        assertEquals(2.25, f.lambdaHome, 0.0);
        assertEquals(0.5, f.lambdaAway, 0.0);
        assertEquals(20690L, f.epochDay);
    }

    @Test
    public void decodePreservesFixtureOrder() throws IOException {
        List<SimContext.League> leagues = new ArrayList<SimContext.League>();
        leagues.add(sampleLeague());
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        fixtures.add(new SimContext.Fixture("Ligue 1", 0, 1, 1.0, 1.0, 20691L));
        fixtures.add(new SimContext.Fixture("Ligue 1", 1, 0, 1.0, 1.0, 20690L));
        SimContext decoded = SimContext.decode(new SimContext(leagues, fixtures).encode());
        assertEquals(20691L, decoded.fixtures.get(0).epochDay);
        assertEquals(20690L, decoded.fixtures.get(1).epochDay);
    }

    @Test
    public void unknownLeagueNameReturnsNull() throws IOException {
        List<SimContext.League> leagues = new ArrayList<SimContext.League>();
        leagues.add(sampleLeague());
        SimContext decoded = SimContext.decode(
                new SimContext(leagues, new ArrayList<SimContext.Fixture>()).encode());
        assertNull(decoded.leagueByName("Premier League"));
    }

    @Test(expected = IOException.class)
    public void unknownLeagueOrdinalRejected() throws IOException {
        SimContext.decode("L\t0\tLigue 1\t2\t4\nT\t0\tParis SG\nT\t0\tLyon\n"
                + "F\t5\t0\t1\t1.0\t1.0\t20690\n");
    }

    @Test(expected = IOException.class)
    public void malformedLineRejected() throws IOException {
        SimContext.decode("X\twhatever\n");
    }

    @Test(expected = IOException.class)
    public void incompleteLeagueRejected() throws IOException {
        // roster without standings/real table rows
        SimContext.decode("L\t0\tLigue 1\t2\t4\nT\t0\tParis SG\nT\t0\tLyon\n");
    }
}
