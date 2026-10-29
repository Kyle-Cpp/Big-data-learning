package com.footpred.backend.repository;

import com.footpred.backend.domain.HistoryRow;
import com.footpred.backend.domain.PlayerProfile;
import com.footpred.backend.domain.SeasonSimulation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public int replacePlayerProfiles(String runId, List<PlayerProfile> profiles) {
        jdbc.update("DELETE FROM player_profile WHERE run_id = ?", runId);
        if (profiles.isEmpty()) {
            return 0;
        }
        // The reducer keys by (team, match, player), so a player who left and
        // returned to the same club within one season produces two rows for the
        // same (player, team). Collapse them by summing the counters, otherwise
        // the unique key rejects the import.
        Map<String, PlayerProfile> merged = new LinkedHashMap<String, PlayerProfile>();
        for (PlayerProfile p : profiles) {
            String key = p.playerId + "\u0000" + (p.teamName == null ? "" : p.teamName);
            PlayerProfile existing = merged.get(key);
            if (existing == null) {
                merged.put(key, copyOf(p));
            } else {
                // Order statistics and counts add up; rates are re-weighted by
                // minutes so a 30-minute cameo cannot outweigh a full season.
                existing.appearances += p.appearances;
                existing.starts += p.starts;
                existing.minutes += p.minutes;
                existing.shots += p.shots;
                existing.goals += p.goals;
                existing.xg += p.xg;
                existing.expectedMinutes += p.expectedMinutes;
                existing.expectedGoals += p.expectedGoals;
                double totalMinutes = existing.minutes;
                if (totalMinutes > 0) {
                    existing.shrunkXgPer90 =
                            (existing.shrunkXgPer90 * (totalMinutes - p.minutes)
                                    + p.shrunkXgPer90 * p.minutes) / totalMinutes;
                }
                existing.rowCount += 1;
            }
        }
        List<Object[]> batch = new ArrayList<Object[]>(merged.size());
        for (PlayerProfile p : merged.values()) {
            // No duplicate was merged for this player, so every derived figure is
            // exactly what the reducer computed — copying it unchanged keeps the
            // stored values comparable with the model's own output.
            boolean untouched = p.rowCount == 1;
            double xg90 = untouched
                    ? p.xgPer90
                    : (p.minutes > 0 ? p.xg * 90.0 / p.minutes : 0.0);
            double shrunk = untouched
                    ? p.shrunkXgPer90
                    : p.shrunkXgPer90;   // already minute-weighted during merge
            batch.add(new Object[]{
                    runId, p.playerId, p.playerName, p.teamName, p.competition, p.position,
                    p.appearances, p.starts, p.minutes, p.shots, p.goals,
                    p.xg, num(xg90), num(shrunk), num(p.expectedMinutes),
                    num(p.expectedGoals), num(p.goalProbability)
            });
        }
        jdbc.batchUpdate("INSERT INTO player_profile (run_id, player_id, player_name, team_name,"
                + " competition, position, appearances, starts, minutes, shots, goals, xg,"
                + " xg_per90, shrunk_xg_per90, expected_minutes, lambda, goal_probability)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch);
        return batch.size();
    }

    private static PlayerProfile copyOf(PlayerProfile source) {
        PlayerProfile copy = new PlayerProfile();
        copy.teamId = source.teamId;
        copy.matchId = source.matchId;
        copy.playerId = source.playerId;
        copy.playerName = source.playerName;
        copy.teamName = source.teamName;
        copy.competition = source.competition;
        copy.position = source.position;
        copy.appearances = source.appearances;
        copy.starts = source.starts;
        copy.minutes = source.minutes;
        copy.shots = source.shots;
        copy.goals = source.goals;
        copy.xg = source.xg;
        copy.xgPer90 = source.xgPer90;
        copy.shrunkXgPer90 = source.shrunkXgPer90;
        copy.expectedMinutes = source.expectedMinutes;
        copy.expectedGoals = source.expectedGoals;
        copy.goalProbability = source.goalProbability;
        return copy;
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
