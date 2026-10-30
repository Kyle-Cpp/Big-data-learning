package com.footpred.common;

/**
 * Aggregation-vector math shared by the map side (per-segment local
 * pre-aggregation) and the reduce side (global) of Job P5.2.
 *
 * Accumulator layout (double vector, one per team):
 *
 *   [0] nSims        number of simulated seasons contributing
 *   [1] sumPoints   final points, summed
 *   [2] sumPointsSq final points squared, summed
 *   [3] sumRank     final rank (1-based), summed
 *   [4] nChampion   rank == 1 count
 *   [5] nTop        rank <= topPlaces count (European spots)
 *   [6] nRelegated  rank in the last relegationPlaces count
 *   [7 .. 7+nTeams-1] rank histogram (count at each 1-based rank)
 *
 * Every field is additive, so the vector sums cleanly through the combiner
 * (VectorSumCombiner) and across mappers.
 */
public final class SimAggregate {

    public static final int HEAD = 7;

    private SimAggregate() {
    }

    /** Fresh accumulator sized for one league with the given team count. */
    public static double[] newAccumulator(int nTeams) {
        if (nTeams < 1) {
            throw new IllegalArgumentException("nTeams must be positive: " + nTeams);
        }
        return new double[HEAD + nTeams];
    }

    /**
     * Records the outcome of one simulated season for one team.
     *
     * @param rank 1-based final rank of the team
     */
    public static void record(double[] acc, int points, int rank,
                              int nTeams, int topPlaces, int relegationPlaces) {
        if (acc.length != HEAD + nTeams) {
            throw new IllegalArgumentException(
                    "accumulator length " + acc.length + " != " + (HEAD + nTeams));
        }
        if (rank < 1 || rank > nTeams) {
            throw new IllegalArgumentException(
                    "rank " + rank + " outside 1.." + nTeams);
        }
        acc[0] += 1.0;
        acc[1] += points;
        acc[2] += (double) points * points;
        acc[3] += rank;
        if (rank == 1) {
            acc[4] += 1.0;
        }
        if (rank <= topPlaces) {
            acc[5] += 1.0;
        }
        if (rank > nTeams - relegationPlaces) {
            acc[6] += 1.0;
        }
        acc[HEAD + rank - 1] += 1.0;
    }

    /**
     * Reduces a fully summed accumulator to the reported statistics.
     * All probabilities/histograms are normalised by the nSims field.
     */
    public static Stats stats(double[] acc) {
        double n = acc[0];
        if (n <= 0.0) {
            throw new IllegalArgumentException("accumulator has no samples");
        }
        double avgPoints = acc[1] / n;
        double variance = Math.max(0.0, acc[2] / n - avgPoints * avgPoints);
        double[] hist = new double[acc.length - HEAD];
        for (int i = 0; i < hist.length; i++) {
            hist[i] = acc[HEAD + i] / n;
        }
        return new Stats(n, avgPoints, Math.sqrt(variance), acc[3] / n,
                acc[4] / n, acc[5] / n, acc[6] / n, hist);
    }

    /** Summary statistics of one team over all simulated seasons. */
    public static final class Stats {
        public final double nSims;
        public final double avgPoints;
        public final double sdPoints;
        public final double avgRank;
        public final double pChampion;
        public final double pTop;
        public final double pRelegated;
        public final double[] rankHist;

        Stats(double nSims, double avgPoints, double sdPoints, double avgRank,
              double pChampion, double pTop, double pRelegated, double[] rankHist) {
            this.nSims = nSims;
            this.avgPoints = avgPoints;
            this.sdPoints = sdPoints;
            this.avgRank = avgRank;
            this.pChampion = pChampion;
            this.pTop = pTop;
            this.pRelegated = pRelegated;
            this.rankHist = rankHist;
        }
    }
}
