package com.footpred.backend.api;

import com.footpred.backend.domain.JobInstance;
import com.footpred.backend.service.ResultImportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Triggers the export-to-MySQL import step.
 *
 * <p>Only present when {@code footpred.mysql.enabled=true}; without a database
 * the endpoints would have nowhere to write, so they are not exposed at all
 * rather than failing at request time.
 *
 * <p>The import is explicit rather than automatic after a job, because the
 * export files are produced by a separate script on the Hadoop host. Making it
 * an endpoint lets an operator re-run the import after fixing a parse issue
 * without touching HDFS.
 */
@RestController
@RequestMapping("/api/admin/import")
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class ImportController {

    private final ResultImportService importService;

    @Value("${footpred.result.module2-evaluation:}")
    private String module2EvaluationLocation;

    @Value("${footpred.result.simulation:}")
    private String simulationLocation;

    public ImportController(ResultImportService importService) {
        this.importService = importService;
    }

    /**
     * Imports every artefact of one run.
     *
     * <p>Locations default to the configured export layout
     * ({@code runs/<runId>/...}); each can be overridden so a partially exported
     * run can still be imported.
     */
    @PostMapping("/run/{runId}")
    public ResponseEntity<Map<String, Object>> importRun(
            @PathVariable String runId,
            @RequestBody(required = false) Map<String, String> overrides) {

        Map<String, String> paths = new LinkedHashMap<String, String>();
        String base = "runs/" + runId;
        paths.put("randomForestPredictions",
                pick(overrides, "randomForestPredictions", base + "/module2/rf4-predictions-test.tsv"));
        paths.put("randomForestEvaluation",
                pick(overrides, "randomForestEvaluation", base + "/module2/evaluation-test.tsv"));
        paths.put("players", pick(overrides, "players", base + "/module3/players.tsv"));
        paths.put("simulation", pick(overrides, "simulation", simulationLocation));
        paths.put("champions", pick(overrides, "champions", base + "/module5/champions.tsv"));
        paths.put("homeAdvantage",
                pick(overrides, "homeAdvantage", base + "/module5/home-advantage.tsv"));
        paths.put("h2h", pick(overrides, "h2h", base + "/module5/h2h.tsv"));
        paths.put("goalMinutes", pick(overrides, "goalMinutes", base + "/module5/goal-minutes.tsv"));

        ResultImportService.ImportReport combined = new ResultImportService.ImportReport();
        combined.runId = runId;

        merge(combined, importService.importMatchPredictions(
                runId, "random-forest", paths.get("randomForestPredictions")));
        merge(combined, importService.importEvaluation(
                runId, "MATCH_OUTCOME", "test", paths.get("randomForestEvaluation")));
        merge(combined, importService.importPlayers(runId, paths.get("players")));
        merge(combined, importService.importSimulation(runId, paths.get("simulation")));
        merge(combined, importService.importHistory(runId, "P6.1", paths.get("champions")));
        merge(combined, importService.importHistory(runId, "P6.2", paths.get("homeAdvantage")));
        merge(combined, importService.importHistory(runId, "P6.4", paths.get("h2h")));
        merge(combined, importService.importHistory(runId, "P6.3", paths.get("goalMinutes")));

        return ResponseEntity.ok(response(combined));
    }

    @PostMapping("/matches/{runId}")
    public ResponseEntity<Map<String, Object>> importMatches(
            @PathVariable String runId,
            @RequestBody Map<String, String> body) {
        ResultImportService.ImportReport report = importService.importMatchPredictions(
                runId, body.getOrDefault("source", "random-forest"), body.get("location"));
        return ResponseEntity.ok(response(report));
    }

    @PostMapping("/history/{runId}")
    public ResponseEntity<Map<String, Object>> importHistory(
            @PathVariable String runId,
            @RequestBody Map<String, String> body) {
        ResultImportService.ImportReport report = importService.importHistory(
                runId, body.get("analysis"), body.get("location"));
        return ResponseEntity.ok(response(report));
    }

    /**
     * Imports one evaluation report.
     *
     * <p>Needed because the three modules write their metrics with different
     * split labels — module 2 uses validation/test/backtest, module 3 writes
     * {@code test.*} and {@code validation.*} prefixes in a single file — so the
     * module and split cannot be inferred from a fixed run layout.
     */
    @PostMapping("/evaluation/{runId}")
    public ResponseEntity<Map<String, Object>> importEvaluation(
            @PathVariable String runId,
            @RequestBody Map<String, String> body) {
        String module = body.containsKey("module") ? body.get("module") : "PLAYER_PREDICTION";
        // Module 3 prefixes every key with its split; splitting the file here
        // keeps one row per (run, module, split, metric) like module 2.
        java.util.Map<String, ResultImportService.ImportReport> bySplit =
                importService.importEvaluationWithSplitPrefix(runId, module, body.get("location"));
        ResultImportService.ImportReport combined = new ResultImportService.ImportReport();
        combined.runId = runId;
        for (ResultImportService.ImportReport part : bySplit.values()) {
            combined.rowCounts.putAll(part.rowCounts);
            combined.skipped.putAll(part.skipped);
        }
        return ResponseEntity.ok(response(combined));
    }

    private static String pick(Map<String, String> overrides, String key, String fallback) {
        if (overrides != null) {
            String value = overrides.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private static void merge(ResultImportService.ImportReport target,
                              ResultImportService.ImportReport source) {
        target.rowCounts.putAll(source.rowCounts);
        target.skipped.putAll(source.skipped);
    }

    private static Map<String, Object> response(ResultImportService.ImportReport report) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runId", report.runId);
        payload.put("status", report.skipped.isEmpty() ? "imported" : "partial");
        payload.put("totalRows", report.totalRows());
        payload.put("rowCounts", report.rowCounts);
        if (!report.skipped.isEmpty()) {
            payload.put("skipped", report.skipped);
        }
        return payload;
    }
}
