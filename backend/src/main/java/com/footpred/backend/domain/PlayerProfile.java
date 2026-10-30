package com.footpred.backend.domain;

/**
 * One player's xG profile (Job P4.4, handoff package).
 *
 * <p>Column order follows {@code PlayerGoalProfileJob.ProfileReducer}, whose
 * reducer key is {@code season|teamId|playerId} and whose value repeats the
 * identifiers so downstream consumers never have to parse the key:
 * {@code season teamId playerId competition teamName playerName position
 * appearances starts minutes shots goals xg xg90 shrunkXg90 expectedMinutes
 * lambda goalProbability}.
 */
public class PlayerProfile {

    public String season;
    public int teamId;
    public int playerId;
    public String playerName;
    public String teamName;
    public String competition;
    public String position;

    public int appearances;
    public int starts;
    public double minutes;
    public int shots;
    public int goals;

    public double xg;
    public double xgPer90;
    /** Shrinkage-corrected xG/90, the figure used for the ranking table. */
    public double shrunkXgPer90;
    public double expectedMinutes;
    public double expectedGoals;
    /** Probability of scoring at least once in the projected appearance. */
    public double goalProbability;

    public PlayerProfile() {
    }
}
