package com.footpred.backend.repository;

import com.footpred.backend.domain.HistoryRow;
import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonSimulation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Bulk import for player profiles, season simulations and history points.
 *
 * <p>All three follow the same shape — parse a whole TSV, replace the previous
 * import for that {@code runId}, then batch insert — so they live together and
 * share the NaN guard.
 */
@Repository
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class AnalyticsImportRepository {

    private final JdbcTemplate jdbc;

    public AnalyticsImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------- players

    @Transactional
    public int replacePlayerProfiles(String runId, List<PlayerProfile> profiles) {
        // The MR reducer already aggregates a whole (season, team, player).
        // Summing these rows would mix seasons and invalidate single-appearance
        // minutes, lambda and probability. Preserve each row exactly.
        java.util.Set<String> keys = new java.util.HashSet<String>();
        List<Object[]> batch = new ArrayList<Object[]>(profiles.size());
        for (PlayerProfile p : profiles) {
            if (p.season == null || p.season.isEmpty() || p.teamId < 0 || p.playerId < 0) {
                throw new IllegalArgumentException("Player profile requires season, teamId and playerId");
            }
            String key = p.season + "|" + p.teamId + "|" + p.playerId;
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate player profile: " + key);
            }
            batch.add(new Object[]{
                    runId, p.season, p.teamId, p.playerId, p.playerName, p.teamName, p.competition, p.position,
                    p.appearances, p.starts, p.minutes, p.shots, p.goals,
                    p.xg, num(p.xgPer90), num(p.shrunkXgPer90), num(p.expectedMinutes),
                    num(p.expectedGoals), num(p.goalProbability)
            });
        }
        jdbc.update("DELETE FROM player_profile WHERE run_id = ?", runId);
        jdbc.batchUpdate("INSERT INTO player_profile (run_id, season, team_id, player_id, player_name, team_name,"
                + " competition, position, appearances, starts, minutes, shots, goals, xg,"
                + " xg_per90, shrunk_xg_per90, expected_minutes, lambda, goal_probability)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch);
        return batch.size();
    }

    // ------------------------------------------------------- simulation

    public int replaceSeasonSimulation(String runId, List<SeasonSimulation> rows) {
        jdbc.update("DELETE FROM season_simulation WHERE run_id = ?", runId);
        if (rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<Object[]>(rows.size());
        for (SeasonSimulation s : rows) {
            batch.add(new Object[]{
                    runId, s.league, s.team,
                    num(s.averagePoints), num(s.pointsStandardDeviation), num(s.averageRank),
                    num(s.championshipProbability), num(s.topPlacesProbability),
                    num(s.relegationProbability),
                    s.actualRank < 0 ? null : s.actualRank,
                    s.actualPoints < 0 ? null : s.actualPoints,
                    s.rankHistogram
            });
        }
        jdbc.batchUpdate("INSERT INTO season_simulation (run_id, league, team, avg_points,"
                + " sd_points, avg_rank, p_champion, p_top, p_relegated, real_rank, real_points,"
                + " rank_hist) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", batch);
        return batch.size();
    }

    // ---------------------------------------------------------- history

    public int replaceHistory(String runId, String analysis, List<HistoryRow> rows) {
        jdbc.update("DELETE FROM history_point WHERE run_id = ? AND analysis = ?", runId, analysis);
        if (rows.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<Object[]>(rows.size());
        for (HistoryRow h : rows) {
            batch.add(new Object[]{
                    runId, analysis, h.league, h.season, h.team, h.player, h.dimension,
                    num(h.value), num(h.secondaryValue), h.count
            });
        }
        jdbc.batchUpdate("INSERT INTO history_point (run_id, analysis, league, season, team,"
                + " player, dimension, value_num, value_second, cnt)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)", batch);
        return batch.size();
    }

    // ------------------------------------------------------------ query

    public int countPlayers(String runId) {
        return countOf("player_profile", runId);
    }

    public int countSimulations(String runId) {
        return countOf("season_simulation", runId);
    }

    public int countHistory(String runId, String analysis) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM history_point WHERE run_id = ? AND analysis = ?",
                Integer.class, runId, analysis);
        return count == null ? 0 : count;
    }

    private int countOf(String table, String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    /** MySQL rejects NaN; NULL keeps the "no data" meaning intact. */
    private static Double num(double value) {
        return Double.isFinite(value) ? value : null;
    }
}
