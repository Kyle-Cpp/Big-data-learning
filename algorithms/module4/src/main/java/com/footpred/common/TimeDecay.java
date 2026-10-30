package com.footpred.common;

/**
 * Dixon-Coles exponential time decay (Dixon &amp; Coles 1997, section 6).
 *
 * Each observation receives likelihood weight
 *
 *     w = exp(-xi * days_ago)
 *
 * where days_ago is measured from the reference date (training cut-off) back
 * to the match date. xi is expressed through the half-life so the value is
 * directly interpretable: xi = ln(2) / halfLifeDays.
 */
public final class TimeDecay {

    public static final double LN2 = Math.log(2.0);

    private TimeDecay() {
    }

    /** Decay rate xi from an interpretable half-life in days. */
    public static double xiFromHalfLifeDays(double halfLifeDays) {
        if (halfLifeDays <= 0) {
            throw new IllegalArgumentException("halfLifeDays must be positive: " + halfLifeDays);
        }
        return LN2 / halfLifeDays;
    }

    /**
     * Time-decay weight of a match.
     *
     * @param xi             decay rate (1/days)
     * @param refEpochDay    reference date (training cut-off) as epoch day
     * @param matchEpochDay  match date as epoch day
     */
    public static double weight(double xi, long refEpochDay, long matchEpochDay) {
        long daysAgo = refEpochDay - matchEpochDay;
        if (daysAgo <= 0) {
            // match dated at or after the reference date: full weight
            return 1.0;
        }
        return Math.exp(-xi * daysAgo);
    }
}
