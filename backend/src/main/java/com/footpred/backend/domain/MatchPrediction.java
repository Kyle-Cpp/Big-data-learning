package com.footpred.backend.domain;

import java.util.List;

/**
 * One row of a match outcome prediction, normalised across both packages.
 *
 * <p>Two very different sources feed this type:
 * <ul>
 *   <li>handoff {@code rf4-predictions-*} — a historical back-test row with a
 *       known result ({@code actual}), 11 columns;</li>
 *   <li>partner {@code predictions/part-m-*} — a forward-looking fixture with
 *       score-matrix detail ({@code topScores}), 7 columns.</li>
 * </ul>
 * {@code actual} and {@code topScores} are therefore nullable: exactly one of
 * the two is populated depending on the origin.
 */
public class MatchPrediction {

    /** Identifier: handoff {@code match_id} (UUID) or partner {@code "A vs B"}. */
    public String matchId;
    public String source;
    public long epochDay;
    public String season;
    public String league;
    public String homeTeam;
    public String awayTeam;

    /** Actual result H/D/A, null for forward fixtures. */
    public String actual;
    /** Hard label chosen after the draw margin, null when not classified. */
    public String predicted;

    public double probabilityHome;
    public double probabilityDraw;
    public double probabilityAway;

    /** Expected goals, partner package only. */
    public Double lambdaHome;
    public Double lambdaAway;

    /** Top scorelines as "2-1:0.0951" entries, partner package only. */
    public List<String> topScores;

    /** True when the hard prediction matches the actual result. */
    public Boolean correct;

    public MatchPrediction() {
    }
}
