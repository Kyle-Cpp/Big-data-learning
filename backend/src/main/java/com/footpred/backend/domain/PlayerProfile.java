package com.footpred.backend.domain;

/**
 * One player's xG profile (Job P4.4, handoff package).
 *
 * <p>Column order follows {@code PlayerGoalProfileJob.ProfileReducer}, whose
 * reducer key is {@code teamId|matchId|playerId} and whose value repeats the
 * identifiers so downstream consumers never have to parse the key:
 * {@code teamId matchId playerId competition teamName playerName position
 * appearances starts minutes shots goals xg xg90 shrunkXg90 expectedMinutes
 * lambda goalProbability}.
 */
public class PlayerProfile {

    public int teamId;
    public String matchId;
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

    /**
     * How many reducer rows this profile was built from.
     *
     * <p>Not a field of the TSV: the importer sets it to 1 for every parsed row
     * and increments it while merging, so downstream code can tell whether any
     * derived rate needs recomputing.
     */
    public int rowCount = 1;

    public PlayerProfile() {
    }
}
