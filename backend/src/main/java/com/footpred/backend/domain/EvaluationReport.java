package com.footpred.backend.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluation metrics for one dataset split.
 *
 * <p>Both packages emit a {@code metric \t value} TSV, but the key sets differ:
 * the handoff {@code rf5-evaluation-*} writes {@code count/accuracy/macro_f1/
 * log_loss/rps/draw_margin} plus confusion matrix and per-class precision;
 * the partner {@code evaluation/part-*} writes a single {@code category=...}
 * header line and then the same style of pairs. Everything is therefore kept as
 * an ordered key-value map, with the metrics the API actually needs promoted to
 * typed fields.
 */
public class EvaluationReport {

    /** Dataset split this report covers: validation / test / backtest / all. */
    public String split;
    /** Match count, null when the source omitted it. */
    public Long matches;
    public Double accuracy;
    public Double macroF1;
    public Double logLoss;
    public Double rps;
    public Double drawMargin;

    /** Every raw key-value pair, preserving source order. */
    public Map<String, String> metrics = new LinkedHashMap<String, String>();

    /** Confusion matrix rows keyed by the actual class label. */
    public Map<String, String> confusion = new LinkedHashMap<String, String>();

    public EvaluationReport() {
    }
}
