package com.footpred.backend.repository;

import com.footpred.backend.domain.MatchPrediction;
import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonSimulation;
import com.footpred.backend.parser.EvaluationParser;
import com.footpred.backend.parser.HistoryParser;
import com.footpred.backend.parser.MatchPredictionParser;
import com.footpred.backend.parser.PlayerProfileParser;
import com.footpred.backend.parser.SeasonSimulationParser;
import com.footpred.backend.domain.EvaluationReport;
import com.footpred.backend.domain.HistoryRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk import against the real exported files.
 *
 * <p>Checks the two properties that matter for a metadata store: the rows land
 * with the right values, and re-importing the same run replaces rather than
 * duplicates. The second is what makes it safe to re-run an import after a
 * partial failure, which will happen in practice.
 */
@SpringBootTest(properties = "footpred.mysql.enabled=true")
@ActiveProfiles("mysql-test")
@Sql(scripts = "classpath:schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ResultImportRepositoryTest {

    @Autowired
    private ResultImportRepository results;

    @Autowired
    private AnalyticsImportRepository analytics;

    @Autowired
    private JdbcTemplate jdbc;

    private List<String> fixture(String name) throws IOException {
        return Files.readAllLines(Paths.get("src/test/resources/fixtures", name),
                StandardCharsets.UTF_8);
    }

    /** The 10k-simulation output, shipped as a test resource (21 KB). */
    List<String> realSimulation() throws IOException {
        return Files.readAllLines(
                Paths.get("src/test/resources/fixtures/simulation_full.tsv"), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- matches

    @Test
    void matchPredictionsLandWithCanonicalCodes() throws IOException {
        String runId = "test-run-matches";
        List<MatchPrediction> rows = MatchPredictionParser.parseRandomForestPrediction(
                fixture("rf4-predictions-sample.tsv"));

        int written = results.replaceMatchPredictions(runId, "random-forest", rows);

        assertEquals(6, written);
        assertEquals(6, results.countMatchPredictions(runId));

        // Season and league must be stored in the canonical form the API speaks.
        String season = jdbc.queryForObject(
                "SELECT season FROM match_prediction WHERE run_id = ? AND match_id = ?",
                String.class, runId, "f7a1c9e2-0001");
        assertEquals("2024-25", season);

        String league = jdbc.queryForObject(
                "SELECT league FROM match_prediction WHERE run_id = ? AND match_id = ?",
                String.class, runId, "f7a1c9e2-0001");
        assertEquals("Premier League", league);
    }

    @Test
    void correctnessFlagIsStored() throws IOException {
        String runId = "test-run-correct";
        results.replaceMatchPredictions(runId, "random-forest",
                MatchPredictionParser.parseRandomForestPrediction(
                        fixture("rf4-predictions-sample.tsv")));

        Integer correct = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_prediction WHERE run_id = ? AND is_correct = 1",
                Integer.class, runId);
        Integer wrong = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_prediction WHERE run_id = ? AND is_correct = 0",
                Integer.class, runId);

        // 4 of the 6 fixture rows agree with the actual result.
        assertEquals(4, correct);
        assertEquals(2, wrong);
    }

    @Test
    void dixonColesRowKeepsLambdasAndScorelines() throws IOException {
        String runId = "test-run-dc";
        results.replaceMatchPredictions(runId, "dixon-coles",
                MatchPredictionParser.parseDixonColesPrediction(
                        fixture("predict-part-m-sample.tsv")));

        Double lambdaHome = jdbc.queryForObject(
                "SELECT lambda_home FROM match_prediction WHERE run_id = ? AND match_id = ?",
                Double.class, runId, "Argentina vs France");
        String topScores = jdbc.queryForObject(
                "SELECT top_scores FROM match_prediction WHERE run_id = ? AND match_id = ?",
                String.class, runId, "Argentina vs France");

        assertEquals(1.523, lambdaHome, 1e-6);
        // Pipe-joined exactly as the TSV held it, so the UI can split it back.
        assertNotNull(topScores);
        assertTrue(topScores.startsWith("1-1:0.1102000000|2-1:"));
    }

    @Test
    void reimportReplacesRatherThanDuplicates() throws IOException {
        String runId = "test-run-idempotent";
        List<MatchPrediction> rows = MatchPredictionParser.parseRandomForestPrediction(
                fixture("rf4-predictions-sample.tsv"));

        results.replaceMatchPredictions(runId, "random-forest", rows);
        results.replaceMatchPredictions(runId, "random-forest", rows);
        results.replaceMatchPredictions(runId, "random-forest", rows);

        assertEquals(6, results.countMatchPredictions(runId));
    }

    @Test
    void bothSourcesCoexistUnderOneRun() throws IOException {
        String runId = "test-run-both";
        results.replaceMatchPredictions(runId, "random-forest",
                MatchPredictionParser.parseRandomForestPrediction(
                        fixture("rf4-predictions-sample.tsv")));
        results.replaceMatchPredictions(runId, "dixon-coles",
                MatchPredictionParser.parseDixonColesPrediction(
                        fixture("predict-part-m-sample.tsv")));

        // 6 back-test rows + 4 forward fixtures.
        assertEquals(10, results.countMatchPredictions(runId));
    }

    @Test
    void nanProbabilityBecomesNullNotZero() throws IOException {
        String runId = "test-run-nan";
        List<MatchPrediction> rows = new java.util.ArrayList<MatchPrediction>();
        MatchPrediction partial = new MatchPrediction();
        partial.matchId = "partial-1";
        partial.homeTeam = "A";
        partial.awayTeam = "B";
        partial.probabilityHome = 0.5;
        partial.probabilityDraw = Double.NaN;
        partial.probabilityAway = 0.5;
        rows.add(partial);

        results.replaceMatchPredictions(runId, "random-forest", rows);

        Double draw = jdbc.queryForObject(
                "SELECT p_draw FROM match_prediction WHERE run_id = ? AND match_id = ?",
                Double.class, runId, "partial-1");
        // Zero would be a fabricated probability; NULL is the honest answer.
        assertEquals(null, draw);
    }

    // -------------------------------------------------------- evaluation

    @Test
    void evaluationMetricsAreStoredAsKeyValueRows() throws IOException {
        String runId = "test-run-eval";
        EvaluationReport report = EvaluationParser.parseFlat("test",
                fixture("evaluation-test.tsv"));

        int written = results.replaceEvaluation(runId, "MATCH_OUTCOME", report);

        assertEquals(report.metrics.size(), written);
        String rps = jdbc.queryForObject(
                "SELECT metric_value FROM evaluation_metric"
                        + " WHERE run_id = ? AND metric_key = 'rps'", String.class, runId);
        assertEquals("0.20162505", rps);

        String accuracy = jdbc.queryForObject(
                "SELECT metric_value FROM evaluation_metric"
                        + " WHERE run_id = ? AND metric_key = 'accuracy'", String.class, runId);
        assertEquals("0.51655251", accuracy);
    }

    @Test
    void evaluationSplitsAreIndependent() throws IOException {
        String runId = "test-run-splits";
        results.replaceEvaluation(runId, "MATCH_OUTCOME",
                EvaluationParser.parseFlat("validation", fixture("evaluation-validation.tsv")));
        results.replaceEvaluation(runId, "MATCH_OUTCOME",
                EvaluationParser.parseFlat("test", fixture("evaluation-test.tsv")));
        results.replaceEvaluation(runId, "MATCH_OUTCOME",
                EvaluationParser.parseFlat("backtest", fixture("evaluation-backtest.tsv")));

        Integer splits = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT split) FROM evaluation_metric WHERE run_id = ?",
                Integer.class, runId);
        assertEquals(3, splits);
    }

    // ----------------------------------------------------------- players

    @Test
    void playerProfilesAreStoredRanked() throws IOException {
        String runId = "test-run-players";
        List<PlayerProfile> profiles = PlayerProfileParser.parse(
                fixture("p4-4-player-predictions-sample.tsv"));

        int written = analytics.replacePlayerProfiles(runId, profiles);
        assertEquals(6, written);
        assertEquals(6, analytics.countPlayers(runId));

        Double messiShrunk = jdbc.queryForObject(
                "SELECT shrunk_xg_per90 FROM player_profile WHERE run_id = ? AND player_id = 5503",
                Double.class, runId);
        assertEquals(0.7421, messiShrunk, 1e-6);
    }

    /**
     * A transfer retains separate profiles under both team ids.
     */
    @Test
    void samePlayerUnderTwoTeamsIsAccepted() {
        String runId = "test-run-transfer";
        java.util.List<PlayerProfile> rows = new java.util.ArrayList<PlayerProfile>();

        PlayerProfile atClubA = new PlayerProfile();
        atClubA.season = "2021-22";
        atClubA.teamId = 217;
        atClubA.playerId = 5503;
        atClubA.playerName = "Lionel Messi";
        atClubA.teamName = "Barcelona";
        atClubA.competition = "La Liga";
        atClubA.appearances = 10;
        atClubA.shrunkXgPer90 = 0.80;
        rows.add(atClubA);

        PlayerProfile atClubB = new PlayerProfile();
        atClubB.season = "2021-22";
        atClubB.teamId = 131;
        atClubB.playerId = 5503;
        atClubB.playerName = "Lionel Messi";
        atClubB.teamName = "Paris Saint-Germain";
        atClubB.competition = "Ligue 1";
        atClubB.appearances = 8;
        atClubB.shrunkXgPer90 = 0.70;
        rows.add(atClubB);

        int written = analytics.replacePlayerProfiles(runId, rows);

        assertEquals(2, written);
        assertEquals(2, analytics.countPlayers(runId));
    }

    @Test
    void crossSeasonProfilesAreNeverSummedAndDuplicateInputPreservesExistingRows() throws IOException {
        String runId = "real-seasons";
        List<PlayerProfile> rows = PlayerProfileParser.parse(fixture("players-real-multi-season.tsv"));
        assertEquals(3, analytics.replacePlayerProfiles(runId, rows));
        assertEquals(3, analytics.replacePlayerProfiles(runId, rows));
        assertEquals(3, analytics.countPlayers(runId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE run_id=? AND team_id=217",
                Integer.class, runId));
        assertEquals(38.91173282, jdbc.queryForObject("SELECT expected_minutes FROM player_profile"
                + " WHERE run_id=? AND season='2005-06'", Double.class, runId), 0.00005);
        assertEquals(0.16611143, jdbc.queryForObject("SELECT goal_probability FROM player_profile"
                + " WHERE run_id=? AND season='2005-06'", Double.class, runId), 0.000001);
        rows.add(rows.get(0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> analytics.replacePlayerProfiles(runId, rows));
        assertEquals(3, analytics.countPlayers(runId));
    }

    // -------------------------------------------------------- simulation

    @Test
    void realSimulationImportsAllTeams() throws IOException {
        String runId = "test-run-sim";
        List<SeasonSimulation> rows = SeasonSimulationParser.parse(realSimulation());

        int written = analytics.replaceSeasonSimulation(runId, rows);

        assertEquals(96, written);
        assertEquals(96, analytics.countSimulations(runId));

        Double bayern = jdbc.queryForObject(
                "SELECT p_champion FROM season_simulation"
                        + " WHERE run_id = ? AND team = 'Bayern Munich'", Double.class, runId);
        assertEquals(0.9804, bayern, 1e-6);
    }

    @Test
    void simulationKeepsRealTableForComparison() throws IOException {
        String runId = "test-run-sim-real";
        analytics.replaceSeasonSimulation(runId,
                SeasonSimulationParser.parse(realSimulation()));

        Integer withReal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM season_simulation"
                        + " WHERE run_id = ? AND real_rank IS NOT NULL", Integer.class, runId);
        // Every team has an actual finishing position, which is what the
        // simulation-versus-reality chart needs.
        assertEquals(96, withReal);
    }

    // ---------------------------------------------------------- history

    @Test
    void championsImportKeepsSeasonLabels() throws IOException {
        String runId = "test-run-champ";
        List<HistoryRow> rows = HistoryParser.parseChampions(
                fixture("p6-1-champions-sample.tsv"));

        int written = analytics.replaceHistory(runId, "P6.1", rows);

        assertEquals(6, written);
        assertEquals(6, analytics.countHistory(runId, "P6.1"));
        String season = jdbc.queryForObject(
                "SELECT season FROM history_point WHERE run_id = ? AND team = 'Leicester City'",
                String.class, runId);
        assertEquals("2015-16", season);
    }

    @Test
    void leagueStyleExplodesIntoOneRowPerMetric() throws IOException {
        String runId = "test-run-style";
        List<HistoryRow> rows = HistoryParser.parseLeagueStyle(
                fixture("p66-league-style-sample.tsv"));

        analytics.replaceHistory(runId, "P6.6", rows);

        // 6 league-seasons x 8 metrics.
        assertEquals(48, analytics.countHistory(runId, "P6.6"));
        Integer withNull = jdbc.queryForObject(
                "SELECT COUNT(*) FROM history_point WHERE run_id = ?"
                        + " AND dimension = 'shotsOnTargetPerGame' AND value_num IS NULL",
                Integer.class, runId);
        // Serie A and Ligue 1 both lack shots-on-target for 2015/16. The gaps must
        // survive as NULL rather than as zeros, otherwise the chart would claim
        // those leagues recorded zero shots on target.
        assertEquals(2, withNull);
    }

    @Test
    void nationalityKeepsSummaryAndDetail() throws IOException {
        String runId = "test-run-nat";
        analytics.replaceHistory(runId, "P6.8",
                HistoryParser.parseNationality(fixture("p68-nationality-sample.tsv")));

        Integer summary = jdbc.queryForObject(
                "SELECT COUNT(*) FROM history_point WHERE run_id = ? AND dimension = 'summary'",
                Integer.class, runId);
        assertTrue(summary >= 2);

        Double foreignShare = jdbc.queryForObject(
                "SELECT value_num FROM history_point WHERE run_id = ?"
                        + " AND dimension = 'summary' AND league = 'Premier League'",
                Double.class, runId);
        assertEquals(0.6520, foreignShare, 1e-6);
    }

    @Test
    void allEightAnalysesCanCoexist() throws IOException {
        String runId = "test-run-all-history";
        analytics.replaceHistory(runId, "P6.1",
                HistoryParser.parseChampions(fixture("p6-1-champions-sample.tsv")));
        analytics.replaceHistory(runId, "P6.2",
                HistoryParser.parseHomeAdvantage(fixture("p6-2-home-advantage-sample.tsv")));
        analytics.replaceHistory(runId, "P6.3",
                HistoryParser.parseGoalMinutes(fixture("p6-3-goal-minutes-sample.tsv")));
        analytics.replaceHistory(runId, "P6.4",
                HistoryParser.parseHeadToHead(fixture("p6-4-h2h-sample.tsv")));
        analytics.replaceHistory(runId, "P6.5",
                HistoryParser.parseCareerCurve(fixture("p65-career-sample.tsv")));
        analytics.replaceHistory(runId, "P6.6",
                HistoryParser.parseLeagueStyle(fixture("p66-league-style-sample.tsv")));
        analytics.replaceHistory(runId, "P6.7",
                HistoryParser.parseScoreDifference(fixture("p67-score-diff-sample.tsv")));
        analytics.replaceHistory(runId, "P6.8",
                HistoryParser.parseNationality(fixture("p68-nationality-sample.tsv")));

        Integer distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT analysis) FROM history_point WHERE run_id = ?",
                Integer.class, runId);
        assertEquals(8, distinct);
    }

    // ------------------------------------------------------------ purge

    @Test
    void deletingRunClearsEveryResultTable() throws IOException {
        String runId = "test-run-purge";
        results.replaceMatchPredictions(runId, "random-forest",
                MatchPredictionParser.parseRandomForestPrediction(
                        fixture("rf4-predictions-sample.tsv")));
        results.replaceEvaluation(runId, "MATCH_OUTCOME",
                EvaluationParser.parseFlat("test", fixture("evaluation-test.tsv")));
        analytics.replacePlayerProfiles(runId,
                PlayerProfileParser.parse(fixture("p4-4-player-predictions-sample.tsv")));
        analytics.replaceSeasonSimulation(runId,
                SeasonSimulationParser.parse(realSimulation()));
        analytics.replaceHistory(runId, "P6.1",
                HistoryParser.parseChampions(fixture("p6-1-champions-sample.tsv")));

        results.deleteRun(runId);

        assertEquals(0, results.countMatchPredictions(runId));
        assertEquals(0, results.countEvaluationMetrics(runId));
        assertEquals(0, analytics.countPlayers(runId));
        assertEquals(0, analytics.countSimulations(runId));
        assertEquals(0, analytics.countHistory(runId, "P6.1"));
    }
}
