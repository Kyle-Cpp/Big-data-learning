package com.footpred.backend.parser;

import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonSimulation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player xG profile and season-simulation output. */
class PlayerAndSimulationParserTest {

    private List<String> fixture(String name) throws IOException {
        return Files.readAllLines(Paths.get("src/test/resources/fixtures", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void playerProfileMapsAllEighteenColumns() throws IOException {
        List<PlayerProfile> rows = PlayerProfileParser.parse(
                fixture("p4-4-player-predictions-sample.tsv"));

        assertEquals(6, rows.size());
        PlayerProfile messi = rows.get(0);
        assertEquals(5503, messi.playerId);
        assertEquals("Lionel Messi", messi.playerName);
        assertEquals("Barcelona", messi.teamName);
        assertEquals("La Liga", messi.competition);
        assertEquals("Right Center Forward", messi.position);
        assertEquals(24, messi.appearances);
        assertEquals(22, messi.starts);
        assertEquals(1980.0, messi.minutes, 1e-6);
        assertEquals(112, messi.shots);
        assertEquals(19, messi.goals);
        assertEquals(17.84, messi.xg, 1e-6);
        assertEquals(0.8109090909, messi.xgPer90, 1e-9);
        assertEquals(0.7421, messi.shrunkXgPer90, 1e-9);
        assertEquals(78.4, messi.expectedMinutes, 1e-6);
        assertEquals(0.6466, messi.expectedGoals, 1e-9);
        assertEquals(0.4761, messi.goalProbability, 1e-9);
    }

    @Test
    void shrunkXgIsNeverAboveRawXgForEstablishedPlayers() throws IOException {
        List<PlayerProfile> rows = PlayerProfileParser.parse(
                fixture("p4-4-player-predictions-sample.tsv"));

        // Shrinkage pulls toward the position prior; with >700 minutes the
        // posterior must stay below the raw rate for these samples.
        PlayerProfile messi = rows.get(0);
        assertTrue(messi.shrunkXgPer90 < messi.xgPer90);
        assertTrue(messi.shrunkXgPer90 > 0.0);
    }

    @Test
    void realSimulationFileIsParsedWithCanonicalLeagueNames() throws IOException {
        List<SeasonSimulation> rows =
                SeasonSimulationParser.parse(fixture("simulation-head.tsv"));

        assertEquals(20, rows.size());
        SeasonSimulation bayern = null;
        for (SeasonSimulation row : rows) {
            if ("Bayern Munich".equals(row.team)) {
                bayern = row;
            }
        }
        assertTrue(bayern != null, "Bayern row should be present");
        assertEquals("Bundesliga", bayern.league);
        assertEquals(83.92, bayern.averagePoints, 1e-6);
        assertEquals(0.9804, bayern.championshipProbability, 1e-9);
        assertEquals(1, bayern.actualRank);
        assertEquals(89, bayern.actualPoints);
        assertTrue(bayern.rankHistogram.startsWith("1:0.980"));
    }

    @Test
    void relegationAndTitleOddsAreBothAvailable() throws IOException {
        List<SeasonSimulation> rows =
                SeasonSimulationParser.parse(fixture("simulation-head.tsv"));

        SeasonSimulation augsburg = rows.get(0);
        assertEquals("Augsburg", augsburg.team);
        assertEquals(0.2031, augsburg.relegationProbability, 1e-9);
        assertEquals(0.0, augsburg.championshipProbability, 1e-9);
        // 18-team Bundesliga: rank 17-18 go down.
        assertEquals(14.15, augsburg.averageRank, 1e-6);
    }
}
