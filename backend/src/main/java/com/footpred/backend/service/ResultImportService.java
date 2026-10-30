package com.footpred.backend.service;

import com.footpred.backend.domain.EvaluationReport;
import com.footpred.backend.domain.HistoryRow;
import com.footpred.backend.domain.JobInstance;
import com.footpred.backend.domain.MatchPrediction;
import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonSimulation;
import com.footpred.backend.parser.EvaluationParser;
import com.footpred.backend.parser.HistoryParser;
import com.footpred.backend.parser.MatchPredictionParser;
import com.footpred.backend.parser.PlayerProfileParser;
import com.footpred.backend.parser.SeasonSimulationParser;
import com.footpred.backend.repository.AnalyticsImportRepository;
import com.footpred.backend.repository.ResultImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports exported result files into MySQL.
 *
 * <p>Deliberately sits between the parsers and the repositories so the two stay
 * independent: parsers know the TSV shapes and nothing about SQL, repositories
 * know SQL and nothing about TSV. This class is the only place that knows both,
 * which is what makes it the single place to look when an import produces
 * unexpected row counts.
 *
 * <p>Every import is transactional and replaces its own slice, so running it
 * twice on the same {@code runId} yields the same table contents rather than
 * duplicates. That matters because the export step is routinely re-run after a
 * job is resumed.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class ResultImportService {

    private static final Logger log = LoggerFactory.getLogger(ResultImportService.class);

    private final ResultReader reader;
    private final ResultImportRepository results;
    private final AnalyticsImportRepository analytics;

    public ResultImportService(ResultReader reader, ResultImportRepository results,
                               AnalyticsImportRepository analytics) {
        this.reader = reader;
        this.results = results;
        this.analytics = analytics;
    }

    /** Summary of one import batch, returned to the operator and the API. */
    public static class ImportReport {
        public String runId;
        public Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
        public Map<String, String> skipped = new LinkedHashMap<String, String>();

        public void add(String table, int rows) {
            rowCounts.put(table, rows);
        }

        public void skip(String what, String reason) {
            skipped.put(what, reason);
        }

        public int totalRows() {
            int total = 0;
            for (Integer value : rowCounts.values()) {
                total += value == null ? 0 : value;
            }
            return total;
        }
    }

    // ----------------------------------------------------------- matches

    @Transactional
    public ImportReport importMatchPredictions(String runId, String source, String location) {
        ImportReport report = new ImportReport();
        report.runId = runId;
        List<String> lines = readOrNull(location);
        if (lines == null) {
            report.skip("match_prediction", "location not readable: " + location);
            return report;
        }
        List<MatchPrediction> predictions = MatchPredictionParser.SOURCE_DIXON_COLES.equals(source)
                ? MatchPredictionParser.parseDixonColesPrediction(lines)
                : MatchPredictionParser.parseRandomForestPrediction(lines);
        if (MatchPredictionParser.SOURCE_DIXON_COLES.equals(source) && predictions.isEmpty()) {
            report.skip("match_prediction[dixon-coles]",
                    "no valid Dixon-Coles rows; existing predictions were preserved");
            return report;
        }
        int rows = results.replaceMatchPredictions(runId, source, predictions);
        report.add("match_prediction[" + source + "]", rows);
        log.info("imported {} match predictions for run={} source={}", rows, runId, source);
        return report;
    }

    // -------------------------------------------------------- evaluation

    @Transactional
    public ImportReport importEvaluation(String runId, String module, String split, String location) {
        ImportReport report = new ImportReport();
        report.runId = runId;
        List<String> lines = readOrNull(location);
        if (lines == null) {
            report.skip("evaluation_metric", "location not readable: " + location);
            return report;
        }
        EvaluationReport parsed = EvaluationParser.parseFlat(split, lines);
        int rows = results.replaceEvaluation(runId, module, parsed);
        report.add("evaluation_metric[" + split + "]", rows);
        return report;
    }

    /**
     * Imports one intermediate evaluation report.
     *
     * <p>Module 3 writes a single file whose keys are prefixed with the split
     * ({@code test.log_loss}, {@code validation.brier}) rather than one file per
     * split. Those rows are split apart here so each lands under its own
     * {@code split} value, which keeps the table shape identical to module 2.
     */
    @Transactional
    public java.util.Map<String, ImportReport> importEvaluationWithSplitPrefix(
            String runId, String module, String location) {
        java.util.Map<String, ImportReport> reports = new LinkedHashMap<String, ImportReport>();
        List<String> lines = readOrNull(location);
        if (lines == null) {
            ImportReport skipped = new ImportReport();
            skipped.runId = runId;
            skipped.skip("evaluation_metric", "location not readable: " + location);
            reports.put("none", skipped);
            return reports;
        }
        // group rows by their split prefix, dropping the prefix from the key
        java.util.Map<String, java.util.List<String>> bySplit =
                new LinkedHashMap<String, java.util.List<String>>();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 2) {
                continue;
            }
            String key = f[0].trim();
            int dot = key.indexOf('.');
            String split = dot > 0 ? key.substring(0, dot) : "test";
            String metric = dot > 0 ? key.substring(dot + 1) : key;
            java.util.List<String> rows = bySplit.get(split);
            if (rows == null) {
                rows = new ArrayList<String>();
                bySplit.put(split, rows);
            }
            rows.add(metric + "\t" + f[1]);
        }
        for (java.util.Map.Entry<String, java.util.List<String>> entry : bySplit.entrySet()) {
            EvaluationReport parsed = EvaluationParser.parseFlat(entry.getKey(), entry.getValue());
            int rows = results.replaceEvaluation(runId, module, parsed);
            ImportReport report = new ImportReport();
            report.runId = runId;
            report.add("evaluation_metric[" + entry.getKey() + "]", rows);
            reports.put(entry.getKey(), report);
        }
        return reports;
    }

    // ----------------------------------------------------------- players

    @Transactional
    public ImportReport importPlayers(String runId, String location) {
        ImportReport report = new ImportReport();
        report.runId = runId;
        List<String> lines = readOrNull(location);
        if (lines == null) {
            report.skip("player_profile", "location not readable: " + location);
            return report;
        }
        List<PlayerProfile> profiles = PlayerProfileParser.parse(lines);
        int rows = analytics.replacePlayerProfiles(runId, profiles);
        report.add("player_profile", rows);
        return report;
    }

    // -------------------------------------------------------- simulation

    @Transactional
    public ImportReport importSimulation(String runId, String location) {
        ImportReport report = new ImportReport();
        report.runId = runId;
        List<String> lines = readOrNull(location);
        if (lines == null) {
            report.skip("season_simulation", "location not readable: " + location);
            return report;
        }
        List<SeasonSimulation> simulations = SeasonSimulationParser.parse(lines);
        int rows = analytics.replaceSeasonSimulation(runId, simulations);
        report.add("season_simulation", rows);
        return report;
    }

    // ----------------------------------------------------------- history

    /**
     * Imports one of the eight historical analyses.
     *
     * @param analysis official id such as {@code P6.6}; accepts {@code p66} too
     */
    @Transactional
    public ImportReport importHistory(String runId, String analysis, String location) {
        ImportReport report = new ImportReport();
        report.runId = runId;
        HistoryRow.HistoryKind kind = HistoryRow.HistoryKind.fromToken(analysis);
        if (kind == null) {
            report.skip("history_point", "unknown analysis: " + analysis);
            return report;
        }
        List<String> lines = readOrNull(location);
        if (lines == null) {
            report.skip("history_point", "location not readable: " + location);
            return report;
        }
        List<HistoryRow> rows = parseHistory(kind, lines);
        int written = analytics.replaceHistory(runId, kind.officialId(), rows);
        report.add("history_point[" + kind.officialId() + "]", written);
        return report;
    }

    private List<HistoryRow> parseHistory(HistoryRow.HistoryKind kind, List<String> lines) {
        switch (kind) {
            case CHAMPION_EVOLUTION:
                return HistoryParser.parseChampions(lines);
            case HOME_ADVANTAGE:
                return HistoryParser.parseHomeAdvantage(lines);
            case GOAL_MINUTES:
                return HistoryParser.parseGoalMinutes(lines);
            case HEAD_TO_HEAD:
                return HistoryParser.parseHeadToHead(lines);
            case LEAGUE_STYLE:
                return HistoryParser.parseLeagueStyle(lines);
            case SCORE_DIFFERENCE:
                return HistoryParser.parseScoreDifference(lines);
            case NATIONALITY:
                return HistoryParser.parseNationality(lines);
            case CAREER_CURVE:
                return HistoryParser.parseCareer(lines);
            default:
                return java.util.Collections.emptyList();
        }
    }

    // ------------------------------------------------------------- util

    private List<String> readOrNull(String location) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        try {
            List<String> lines = reader.readAllLines(location.trim());
            return lines.isEmpty() ? null : lines;
        } catch (IOException e) {
            log.warn("cannot read {}: {}", location, e.getMessage());
            return null;
        }
    }
}
