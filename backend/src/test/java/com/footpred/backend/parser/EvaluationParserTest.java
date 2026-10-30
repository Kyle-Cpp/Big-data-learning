package com.footpred.backend.parser;

import com.footpred.backend.domain.EvaluationReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * Validates the evaluation parser against the real club-rf-v4 report shipped in
 * the fixed handoff package, so the numbers asserted here are the ones the
 * project actually published.
 */
class EvaluationParserTest {

    private List<String> fixture(String name) throws IOException {
        return Files.readAllLines(Paths.get("src/test/resources/fixtures", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void realV4TestReportIsParsedExactly() throws IOException {
        EvaluationReport report =
                EvaluationParser.parseFlat("test", fixture("evaluation-test.tsv"));

        assertEquals("test", report.split);
        assertEquals(1752L, report.matches.longValue());
        assertEquals(0.51655251, report.accuracy, 1e-9);
        assertEquals(0.45769241, report.macroF1, 1e-9);
        assertEquals(0.98934115, report.logLoss, 1e-9);
        assertEquals(0.20162505, report.rps, 1e-9);
        assertEquals(0.11, report.drawMargin, 1e-9);
    }

    @Test
    void validationAndBacktestDifferAsPublished() throws IOException {
        EvaluationReport validation =
                EvaluationParser.parseFlat("validation", fixture("evaluation-validation.tsv"));
        EvaluationReport backtest =
                EvaluationParser.parseFlat("backtest", fixture("evaluation-backtest.tsv"));

        // RESULTS_MODULE2_CLUB_V4.md: validation RPS 0.1949, backtest 0.2038.
        assertTrue(validation.rps < backtest.rps,
                "validation RPS should be better than backtest RPS");
        assertTrue(validation.accuracy > backtest.accuracy,
                "validation accuracy should exceed backtest accuracy");
    }

    @Test
    void confusionMatrixIsSeparatedFromFlatMetrics() throws IOException {
        EvaluationReport report =
                EvaluationParser.parseFlat("test", fixture("evaluation-test.tsv"));

        assertEquals(3, report.confusion.size());
        assertNotNull(report.confusion.get("H"));
        assertNotNull(report.confusion.get("D"));
        assertNotNull(report.confusion.get("A"));
        // Row sums must equal the actual class counts in the v4 test set.
        assertEquals(736, rowSum(report.confusion.get("H")));
        assertEquals(437, rowSum(report.confusion.get("D")));
        assertEquals(579, rowSum(report.confusion.get("A")));
    }

    @Test
    void perClassMetricsSurviveInRawMap() throws IOException {
        EvaluationReport report =
                EvaluationParser.parseFlat("test", fixture("evaluation-test.tsv"));

        assertNotNull(report.metrics.get("precision_D"));
        assertNotNull(report.metrics.get("recall_D"));
        assertNotNull(report.metrics.get("f1_D"));
        assertEquals("0.17391304", report.metrics.get("recall_D"));
    }

    @Test
    void stratifiedPartnerFormatYieldsOneReportPerCategory() {
        List<String> lines = new ArrayList<String>();
        lines.add("category=all model=B0-uniform matches=1390 rps=0.2241 logloss=1.0986 accuracy=0.3331");
        lines.add("category=all model=DC-full matches=1390 rps=0.1675 logloss=0.8565 accuracy=0.6122");
        lines.add("category=major model=DC-full matches=520 rps=0.1601 logloss=0.8401 accuracy=0.6300");

        List<EvaluationReport> reports = EvaluationParser.parseStratified(lines);
        assertEquals(3, reports.size());

        EvaluationReport dcFull = reports.get(1);
        assertEquals("all", dcFull.split);
        assertEquals(1390L, dcFull.matches.longValue());
        assertEquals(0.1675, dcFull.rps, 1e-9);
        assertEquals(0.6122, dcFull.accuracy, 1e-9);
    }

    @Test
    void missingMetricStaysNullInsteadOfZero() {
        List<String> lines = new ArrayList<String>();
        lines.add("count\t10");
        lines.add("accuracy\t0.5");

        EvaluationReport report = EvaluationParser.parseFlat("test", lines);
        assertEquals(10L, report.matches.longValue());
        assertEquals(0.5, report.accuracy, 1e-9);
        assertNull(report.rps);
        assertNull(report.logLoss);
    }

    private int rowSum(String row) {
        int total = 0;
        for (String part : row.split(",")) {
            total += Integer.parseInt(part.trim());
        }
        return total;
    }
}
