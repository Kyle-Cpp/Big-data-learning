package com.footpred.backend.parser;

import com.footpred.backend.domain.EvaluationReport;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the two evaluation report layouts.
 *
 * <p>Both write a flat metric table, but they differ in how the split is named:
 * <ul>
 *   <li>handoff {@code rf5-evaluation-*}: the split lives in the directory name,
 *       so the caller passes it in;</li>
 *   <li>partner {@code evaluation/part-*}: the split is embedded as
 *       {@code category=major} and repeats for every stratified group plus an
 *       {@code all} roll-up, so one file yields several reports.</li>
 * </ul>
 */
public final class EvaluationParser {

    private EvaluationParser() {
    }

    /** P5.4 is league TAB metric TAB value; never parse it as RF metrics. */
    public static List<EvaluationReport> parseSeasonReplay(List<String> lines) {
        java.util.Map<String, EvaluationReport> groups = new java.util.LinkedHashMap<String, EvaluationReport>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) continue;
            String[] f = Tsv.split(line);
            if (f.length != 3 || !f[1].matches("teams|points_mae|rank_mae|title_brier|top_brier|relegation_brier")) {
                throw new IllegalArgumentException("Invalid P5.4 metric row");
            }
            double value = Tsv.toDouble(f[2], Double.NaN);
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid P5.4 metric value");
            String league = com.footpred.backend.domain.LeagueCode.canonical(f[0]);
            EvaluationReport report = groups.get(league);
            if (report == null) { report = new EvaluationReport(); report.split = league; groups.put(league, report); }
            if (report.metrics.put(f[1], f[2]) != null) throw new IllegalArgumentException("Duplicate P5.4 metric");
        }
        for (EvaluationReport report : groups.values()) {
            if (report.metrics.size() != 6) throw new IllegalArgumentException("Incomplete P5.4 league metrics");
        }
        return new ArrayList<EvaluationReport>(groups.values());
    }

    /**
     * Handoff format: one metric per line, {@code key <TAB> value}.
     *
     * @param split dataset label (validation / test / backtest)
     */
    public static EvaluationReport parseFlat(String split, List<String> lines) {
        EvaluationReport report = new EvaluationReport();
        report.split = split;
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 2) {
                continue;
            }
            // Skip an optional "metric<TAB>value" banner from the export script.
            if ("metric".equalsIgnoreCase(f[0].trim())) {
                continue;
            }
            apply(report, f[0].trim(), f[1].trim());
        }
        return report;
    }

    /**
     * Partner format: {@code category=<name> matches=.. meanRPS=.. accuracy=..},
     * one line per stratified category.
     */
    public static List<EvaluationReport> parseStratified(List<String> lines) {
        List<EvaluationReport> reports = new ArrayList<EvaluationReport>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("category=")) {
                continue;
            }
            EvaluationReport report = new EvaluationReport();
            for (String token : trimmed.split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = token.substring(0, eq);
                String value = token.substring(eq + 1);
                if ("category".equals(key)) {
                    report.split = value;
                } else {
                    apply(report, key, value);
                }
            }
            reports.add(report);
        }
        return reports;
    }

    private static void apply(EvaluationReport report, String key, String value) {
        if ("count".equals(key)) {
            report.matches = (long) Tsv.toDouble(value, 0d);
        } else if ("matches".equals(key)) {
            report.matches = (long) Tsv.toDouble(value, 0d);
        } else if ("accuracy".equals(key)) {
            report.accuracy = Tsv.toNullableDouble(value);
        } else if ("macro_f1".equals(key) || "macroF1".equals(key)) {
            report.macroF1 = Tsv.toNullableDouble(value);
        } else if ("log_loss".equals(key) || "meanLogLoss".equals(key) || "logloss".equals(key)) {
            report.logLoss = Tsv.toNullableDouble(value);
        } else if ("rps".equals(key) || "meanRPS".equals(key) || "meanRps".equals(key)) {
            report.rps = Tsv.toNullableDouble(value);
        } else if ("draw_margin".equals(key)) {
            report.drawMargin = Tsv.toNullableDouble(value);
        } else if (key.startsWith("confusion_actual_")) {
            report.confusion.put(key.substring("confusion_actual_".length()), value);
        }
        report.metrics.put(key, value);
    }
}
