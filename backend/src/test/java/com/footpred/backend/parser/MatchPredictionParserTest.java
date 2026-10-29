package com.footpred.backend.parser;

import com.footpred.backend.domain.MatchPrediction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks both prediction formats against fixture files whose column order was
 * taken from the emitting reducer.
 */
class MatchPredictionParserTest {

    private List<String> fixture(String name) throws IOException {
        return Files.readAllLines(Paths.get("src/test/resources/fixtures", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void randomForestRowIsFullyMapped() throws IOException {
        List<MatchPrediction> rows =
                MatchPredictionParser.parseRandomForestPrediction(fixture("rf4-predictions-sample.tsv"));

        assertEquals(6, rows.size());
        MatchPrediction first = rows.get(0);
        assertEquals(MatchPredictionParser.SOURCE_RANDOM_FOREST, first.source);
        assertEquals("Arsenal", first.homeTeam);
        assertEquals("Chelsea", first.awayTeam);
        assertEquals("H", first.actual);
        assertEquals("H", first.predicted);
        assertEquals(0.512, first.probabilityHome, 1e-9);
        assertEquals(0.241, first.probabilityDraw, 1e-9);
        assertEquals(0.247, first.probabilityAway, 1e-9);
    }

    @Test
    void seasonAndLeagueAreNormalisedOnRead() throws IOException {
        List<MatchPrediction> rows =
                MatchPredictionParser.parseRandomForestPrediction(fixture("rf4-predictions-sample.tsv"));

        // The handoff package stores "2425" and "premier-league"; the API must not.
        assertEquals("2024-25", rows.get(0).season);
        assertEquals("Premier League", rows.get(0).league);
    }

    @Test
    void correctnessIsDerivedOnlyWhenBothLabelsExist() throws IOException {
        List<MatchPrediction> rows =
                MatchPredictionParser.parseRandomForestPrediction(fixture("rf4-predictions-sample.tsv"));

        assertTrue(rows.get(0).correct);   // H vs H
        assertTrue(!rows.get(1).correct);  // D vs H
        assertTrue(!rows.get(4).correct);  // H vs A
    }

    @Test
    void dixonColesRowKeepsScoreMatrixDetail() throws IOException {
        List<MatchPrediction> rows =
                MatchPredictionParser.parseDixonColesPrediction(fixture("predict-part-m-sample.tsv"));

        assertEquals(4, rows.size());
        MatchPrediction first = rows.get(0);
        assertEquals(MatchPredictionParser.SOURCE_DIXON_COLES, first.source);
        assertEquals("Argentina", first.homeTeam);
        assertEquals("France", first.awayTeam);
        assertEquals(0.4123, first.probabilityHome, 1e-9);
        assertEquals(1.523, first.lambdaHome, 1e-9);
        assertEquals(1.102, first.lambdaAway, 1e-9);
        assertNotNull(first.topScores);
        assertEquals(3, first.topScores.size());
        assertEquals("1-1:0.1102000000", first.topScores.get(0));
    }

    @Test
    void dixonColesRowHasNoActualOrPredictedLabel() throws IOException {
        List<MatchPrediction> rows =
                MatchPredictionParser.parseDixonColesPrediction(fixture("predict-part-m-sample.tsv"));

        // Forward fixtures are not scored; inventing a label would corrupt the
        // accuracy figure the dashboard shows.
        assertNull(rows.get(0).actual);
        assertNull(rows.get(0).predicted);
        assertNull(rows.get(0).correct);
    }

    @Test
    void matchKeySplitsOnFirstSeparatorOnly() {
        String[] pair = MatchPredictionParser.splitMatchKey("Bosnia and Herzegovina vs Trinidad and Tobago");
        assertEquals("Bosnia and Herzegovina", pair[0]);
        assertEquals("Trinidad and Tobago", pair[1]);
    }

    /**
     * The export script prepends a header so the TSV opens cleanly in Excel.
     * That row reached the database as a prediction during the first real import
     * and failed on {@code actual} being 6 characters wide, so it is pinned here.
     */
    @Test
    void headerRowFromExportScriptIsNotTreatedAsAPrediction() {
        List<String> lines = new ArrayList<String>();
        lines.add("match_id	epoch_day	season	league	home_team	away_team	"
                + "actual	predicted	p_home	p_draw	p_away");
        lines.add("f7a1-1	20639	2425	premier-league	A	B	H	H	0.5	0.2	0.3");

        List<MatchPrediction> rows = MatchPredictionParser.parseRandomForestPrediction(lines);

        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).homeTeam);
    }

    @Test
    void malformedLinesAreSkippedNotFatal() {
        List<String> lines = new ArrayList<String>();
        lines.add("");
        lines.add("   ");
        lines.add("too\tfew\tcolumns");
        lines.add("f7a1-1\t20639\t2425\tpremier-league\tA\tB\tH\tH\t0.5\t0.2\t0.3");

        List<MatchPrediction> rows = MatchPredictionParser.parseRandomForestPrediction(lines);
        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).homeTeam);
    }

    @Test
    void emptyProbabilityFieldsBecomeNaN() {
        List<String> lines = new ArrayList<String>();
        lines.add("k\t1\t2425\tpremier-league\tA\tB\t\t\t0.5\t\t0.3");

        List<MatchPrediction> rows = MatchPredictionParser.parseRandomForestPrediction(lines);
        assertTrue(Double.isNaN(rows.get(0).probabilityDraw));
        assertNull(rows.get(0).correct);
    }

    private static InputStream unused() {
        return null;
    }
}
