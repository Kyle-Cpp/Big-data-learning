package com.footpred.backend.parser;

import com.footpred.backend.domain.HistoryRow;
import com.footpred.backend.domain.HistoryRow.HistoryKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the eight history analyses.
 *
 * <p>The shapes differ enough that a single round-trip assertion is meaningless,
 * so each analysis is checked on the semantic property a chart depends on: the
 * champion name, the metric count per league, the bucket ordering, the record
 * split between detail and summary, and so on.
 */
class HistoryParserTest {

    private List<String> fixture(String name) throws IOException {
        return Files.readAllLines(Paths.get("src/test/resources/fixtures", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void championsKeepLeagueSeasonAndChampion() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseChampions(fixture("p6-1-champions-sample.tsv"));

        assertEquals(6, rows.size());
        HistoryRow first = rows.get(0);
        assertEquals(HistoryKind.CHAMPION_EVOLUTION, first.kind);
        // "1516" must come back canonicalised for the API contract.
        assertEquals("2015-16", first.season);
        assertEquals("Leicester City", first.team);
        assertEquals("Premier League", first.league);
        assertEquals(38L, first.count);   // matches played
    }

    @Test
    void homeAdvantageExposesBothWinRates() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseHomeAdvantage(
                fixture("p6-2-home-advantage-sample.tsv"));

        assertEquals(4, rows.size());
        HistoryRow first = rows.get(0);
        assertEquals(HistoryKind.HOME_ADVANTAGE, first.kind);
        assertEquals("Premier League", first.league);
        assertEquals("2015-16", first.season);
        assertEquals(380L, first.count);
        assertEquals(0.3894736842, first.value, 1e-9);          // home win rate
        assertEquals(0.3684210526, first.secondaryValue, 1e-9); // away win rate
    }

    @Test
    void goalMinuteBucketsStayOrdered() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseGoalMinutes(
                fixture("p6-3-goal-minutes-sample.tsv"));

        assertEquals(6, rows.size());
        assertEquals("0-15", rows.get(0).dimension);
        assertEquals("76-90", rows.get(5).dimension);
        assertEquals("Premier League", rows.get(0).league);
        assertEquals("2015-16", rows.get(0).season);
        // The last bucket carries the largest share in this fixture.
        assertTrue(rows.get(5).value > rows.get(0).value);
    }

    @Test
    void headToHeadCombinesTeamPairIntoOneLabel() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseHeadToHead(fixture("p6-4-h2h-sample.tsv"));

        assertEquals(3, rows.size());
        HistoryRow first = rows.get(0);
        assertEquals(HistoryKind.HEAD_TO_HEAD, first.kind);
        assertEquals("Arsenal vs Chelsea", first.team);
        assertEquals(62L, first.count);
        assertEquals(0.4354838710, first.value, 1e-9);
        assertEquals(0.3064516129, first.secondaryValue, 1e-9);
    }

    @Test
    void careerRowsSeparateClubFromInternational() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseCareerCurve(fixture("p65-career-sample.tsv"));

        assertEquals(8, rows.size());
        assertEquals(HistoryKind.CAREER_CURVE, rows.get(0).kind);
        assertEquals("club", rows.get(0).dimension);
        assertEquals("2011-12", rows.get(2).season);
        assertEquals(50.0, rows.get(2).value, 1e-9);   // goals in 2011/12
        assertEquals("intl", rows.get(5).dimension);
        assertEquals(2022, Integer.parseInt(rows.get(7).season));
    }

    @Test
    void leagueStyleExplodesEachRowIntoOnePerMetric() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseLeagueStyle(
                fixture("p66-league-style-sample.tsv"));

        // 6 league-seasons x 8 metrics.
        assertEquals(48, rows.size());
        HistoryRow goalsPerGame = rows.get(0);
        assertEquals(HistoryKind.LEAGUE_STYLE, goalsPerGame.kind);
        assertEquals("goalsPerGame", goalsPerGame.dimension);
        assertEquals(2.730, goalsPerGame.value, 1e-9);
        assertEquals(380L, goalsPerGame.count);

        HistoryRow shotsOnTarget = rows.get(3);
        assertEquals("shotsOnTargetPerGame", shotsOnTarget.dimension);
        assertEquals(8.700, shotsOnTarget.value, 1e-9);
    }

    @Test
    void missingLeagueStyleMetricBecomesNaNRatherThanZero() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseLeagueStyle(
                fixture("p66-league-style-sample.tsv"));

        // Serie A 2015/16 has shots but leaves shots-on-target empty, mirroring
        // the real coverage gap documented in data_assessment.md.
        HistoryRow serieASot = null;
        HistoryRow serieAShots = null;
        for (HistoryRow row : rows) {
            if ("Serie A".equals(row.league)) {
                if ("shotsOnTargetPerGame".equals(row.dimension)) {
                    serieASot = row;
                } else if ("shotsPerGame".equals(row.dimension)) {
                    serieAShots = row;
                }
            }
        }
        assertTrue(serieASot != null, "Serie A shots-on-target row should exist");
        assertTrue(serieAShots != null, "Serie A shots row should exist");
        assertTrue(Double.isNaN(serieASot.value),
                "an unrecorded metric must not be reported as zero");
        assertEquals(25.400, serieAShots.value, 1e-9,
                "a recorded metric next to a gap must still parse");
    }

    @Test
    void scoreDifferenceKeepsSourceAndBucket() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseScoreDifference(
                fixture("p67-score-diff-sample.tsv"));

        assertEquals(13, rows.size());
        assertEquals(HistoryKind.SCORE_DIFFERENCE, rows.get(0).kind);
        assertEquals("intl", rows.get(0).league);   // source, not a league
        assertEquals("2020s", rows.get(0).season);  // decade, not a season
        assertEquals("-5", rows.get(0).dimension);
        assertEquals(3.14, rows.get(0).value, 1e-9);
    }

    @Test
    void nationalitySummaryPrecedesDetail() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseNationality(
                fixture("p68-nationality-sample.tsv"));

        assertTrue(rows.size() >= 4);
        // Summaries are emitted first so a chart can read row 0..n as seasons.
        HistoryRow premierSummary = rows.get(0);
        assertEquals(HistoryKind.NATIONALITY, premierSummary.kind);
        assertEquals("Premier League", premierSummary.league);
        assertEquals("2015-16", premierSummary.season);
        assertEquals("summary", premierSummary.dimension);
        assertEquals(71L, premierSummary.count);            // distinct countries
        assertEquals(0.6520, premierSummary.value, 1e-9);   // foreign share
    }

    @Test
    void nationalityDetailRowsAreRetained() throws IOException {
        List<HistoryRow> rows = HistoryParser.parseNationality(
                fixture("p68-nationality-sample.tsv"));

        HistoryRow england = null;
        for (HistoryRow row : rows) {
            if ("England".equals(row.dimension)) {
                england = row;
            }
        }
        assertTrue(england != null, "detail rows must not be dropped");
        assertEquals(386L, england.count);
        assertEquals(241.0, england.secondaryValue, 1e-9);
    }

    @Test
    void historyKindAcceptsBothIdentifierSchemes() {
        assertEquals(HistoryKind.CAREER_CURVE, HistoryKind.fromToken("p65"));
        assertEquals(HistoryKind.CAREER_CURVE, HistoryKind.fromToken("P6.5"));
        assertEquals(HistoryKind.CHAMPION_EVOLUTION, HistoryKind.fromToken("champions"));
        assertEquals(HistoryKind.CHAMPION_EVOLUTION, HistoryKind.fromToken("P6.1"));
        assertNull(HistoryKind.fromToken("P6.9"));
        assertNull(HistoryKind.fromToken(null));
    }

    @Test
    void officialIdsSpanBothPackagesWithoutCollision() {
        // Eight analyses, four from each side, with no duplicated identifier.
        List<String> ids = new ArrayList<String>();
        for (HistoryKind kind : HistoryKind.values()) {
            ids.add(kind.officialId());
        }
        assertEquals(8, ids.size());
        assertEquals(8, new java.util.HashSet<String>(ids).size());
    }
}
