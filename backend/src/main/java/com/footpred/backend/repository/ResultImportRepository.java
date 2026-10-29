package com.footpred.backend.repository;

import com.footpred.backend.domain.EvaluationReport;
import com.footpred.backend.domain.MatchPrediction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bulk import of match predictions and evaluation metrics.
 *
 * <p>Two conventions inherited from the algorithm layer shape this class:
 * <ul>
 *   <li>results are written <b>after</b> a job succeeds, never by a Reducer, so
 *       the whole payload is available at once and can be batched;</li>
 *   <li>re-importing the same {@code runId} must be idempotent rather than
 *       duplicating rows, so each import deletes that run's slice first.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class ResultImportRepository {

    private final JdbcTemplate jdbc;

    public ResultImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------------------------------------- matches

    /**
     * Replaces the predictions of one run/source and inserts the new ones.
     *
     * @return number of rows written
     */
    public int replaceMatchPredictions(String runId, String source,
                                       List<MatchPrediction> predictions) {
        jdbc.update("DELETE FROM match_prediction WHERE run_id = ? AND source = ?", runId, source);
        if (predictions.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<Object[]>(predictions.size());
        for (MatchPrediction p : predictions) {
            batch.add(new Object[]{
                    runId,
                    source,
                    p.matchId,
                    p.season,
                    p.league,
                    p.epochDay == 0L ? null : (int) p.epochDay,
                    p.homeTeam,
                    p.awayTeam,
                    p.actual,
                    p.predicted,
                    finiteOrNull(p.probabilityHome),
                    finiteOrNull(p.probabilityDraw),
                    finiteOrNull(p.probabilityAway),
                    p.lambdaHome,
                    p.lambdaAway,
                    p.topScores == null ? null : String.join("|", p.topScores),
                    p.correct == null ? null : (p.correct ? 1 : 0)
            });
        }
        jdbc.batchUpdate("INSERT INTO match_prediction (run_id, source, match_id, season, league,"
                + " epoch_day, home_team, away_team, actual, predicted, p_home, p_draw, p_away,"
                + " lambda_home, lambda_away, top_scores, is_correct)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch);
        return batch.size();
    }

    /**
     * Numeric columns must not receive NaN: MySQL rejects it and H2 stores it as
     * NULL with a warning, so both readers would diverge from the TSV.
     */
    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    // -------------------------------------------------------- evaluation

    /** Replaces the metric rows of one run/module/split. */
    public int replaceEvaluation(String runId, String module, EvaluationReport report) {
        jdbc.update("DELETE FROM evaluation_metric WHERE run_id = ? AND module = ? AND split = ?",
                runId, module, report.split);
        List<Object[]> batch = new ArrayList<Object[]>();
        for (Map.Entry<String, String> entry : report.metrics.entrySet()) {
            batch.add(new Object[]{runId, module, report.split,
                    entry.getKey(), truncate(entry.getValue(), 128)});
        }
        if (batch.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("INSERT INTO evaluation_metric (run_id, module, split, metric_key,"
                + " metric_value) VALUES (?,?,?,?,?)", batch);
        return batch.size();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------ query

    public int countMatchPredictions(String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_prediction WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    public int countEvaluationMetrics(String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_metric WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    public int deleteRun(String runId) {
        int rows = 0;
        rows += jdbc.update("DELETE FROM match_prediction WHERE run_id = ?", runId);
        rows += jdbc.update("DELETE FROM evaluation_metric WHERE run_id = ?", runId);
        rows += jdbc.update("DELETE FROM player_profile WHERE run_id = ?", runId);
        rows += jdbc.update("DELETE FROM season_simulation WHERE run_id = ?", runId);
        rows += jdbc.update("DELETE FROM history_point WHERE run_id = ?", runId);
        return rows;
    }
}
