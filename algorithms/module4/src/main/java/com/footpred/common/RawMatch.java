package com.footpred.common;

import java.time.LocalDate;

/**
 * One parsed row of martj42/international_results results.csv.
 *
 * Field order of the source CSV:
 * date, home_team, away_team, home_score, away_score, tournament, city, country, neutral
 *
 * Scores are the "including extra time, excluding penalty shoot-out" convention
 * used by the source dataset; the whole model follows that convention.
 */
public class RawMatch {

    public final long epochDay;
    public final String homeTeam;
    public final String awayTeam;
    public final int homeScore;
    public final int awayScore;
    public final String tournament;
    public final boolean neutral;

    public RawMatch(long epochDay, String homeTeam, String awayTeam,
                    int homeScore, int awayScore, String tournament, boolean neutral) {
        this.epochDay = epochDay;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.tournament = tournament;
        this.neutral = neutral;
    }

    public LocalDate date() {
        return LocalDate.ofEpochDay(epochDay);
    }

    public boolean isFriendly() {
        return "Friendly".equalsIgnoreCase(tournament);
    }
}
