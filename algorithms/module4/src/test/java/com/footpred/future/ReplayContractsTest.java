package com.footpred.future;

import com.footpred.common.DCModel;
import com.footpred.common.RawMatch;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ReplayContractsTest {
    @Test public void unicodeMatchSurvivesIntermediateContract() {
        RawMatch m = new RawMatch(20000, "Bayern München", "Köln", 3, 1, "Bundesliga", false);
        RawMatch result = ReplaySupport.match(ReplaySupport.match(m));
        assertEquals(m.homeTeam, result.homeTeam); assertEquals(m.awayTeam, result.awayTeam);
        assertEquals(3, result.homeScore); assertEquals(1, result.awayScore);
        assertEquals(m.epochDay, result.epochDay); assertFalse(result.neutral);
    }

    @Test public void changingHeldOutScoresCannotChangeFixtureContract() {
        RawMatch first = new RawMatch(20000, "A", "B", 0, 0, "Premier League", false);
        RawMatch changed = new RawMatch(20000, "A", "B", 17, 23, "Premier League", false);
        assertEquals(ReplaySupport.fixture(first), ReplaySupport.fixture(changed));
        assertEquals(5, ReplaySupport.fixture(first).split("\t").length);
        assertNotEquals(ReplaySupport.match(first), ReplaySupport.match(changed));
    }

    @Test public void fixtureRatesUseExistingDcInferenceIncludingNeutralVenue() {
        DCModel model = new DCModel(2, 20000); model.teamNames.addAll(Arrays.asList("A", "B"));
        model.attack[0] = 0.3; model.attack[1] = -0.2;
        model.defense[0] = 0.1; model.defense[1] = 0.2; model.homeAdv = 0.25;
        assertEquals(Math.exp(0.75), model.lambdaHome(0, 1, false), 1e-12);
        assertEquals(Math.exp(0.5), model.lambdaHome(0, 1, true), 1e-12);
        assertEquals(Math.exp(-0.1), model.lambdaAway(0, 1), 1e-12);
    }

    @Test public void relegationRulesRemainCompatibleWithLegacyAndOverride() {
        Configuration c = new Configuration(false);
        assertEquals(2, ReplaySupport.relegation("Bundesliga", c));
        assertEquals(2, ReplaySupport.relegation("Ligue 1", c));
        assertEquals(3, ReplaySupport.relegation("Premier League", c));
        c.setInt("replay.relegation", 1);
        assertEquals(1, ReplaySupport.relegation("Premier League", c));
    }
}
