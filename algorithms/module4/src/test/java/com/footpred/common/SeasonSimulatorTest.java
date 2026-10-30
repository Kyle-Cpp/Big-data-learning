package com.footpred.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Offline end-to-end tests of the Job P5.2 simulation core: synthetic leagues
 * with hand-computable expectations, mirroring the mapper's rollout loop and
 * the reducer's aggregation math without MapReduce.
 */
public class SeasonSimulatorTest {

    private static final List<String> TEAMS =
            Arrays.asList("A", "B", "C", "D");

    private static SimContext.League league(int rel, int top, int[] points,
                                            int[] realPoints, int[] realRank) {
        return new SimContext.League("Test League", rel, top, TEAMS,
                points, new int[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0},
                new int[]{points[0], points[1], points[2], points[3]},
                realPoints, realRank);
    }

    @Test
    public void cutoffAtSeasonEndReproducesActualTableExactly() throws Exception {
        // every match played before the cutoff: nothing left to sample,
        // each rollout must equal the actual final table
        int[] played = {3, 6, 4, 1};
        int[] realPoints = {3, 6, 4, 1};
        int[] realRank = {3, 1, 2, 4};
        SimContext ctx = new SimContext(
                Arrays.asList(league(1, 2, played, realPoints, realRank)),
                new ArrayList<SimContext.Fixture>());
        Random rnd = new Random(5L);

        // sanity: the given real rank matches the documented tie-break order
        LeagueTable check = new LeagueTable("Test League", TEAMS);
        for (int i = 0; i < 4; i++) {
            check.points[i] = played[i];
        }
        assertArrayEquals(realRank, check.ranks());

        double[][] accs = new double[4][];
        for (int i = 0; i < 4; i++) {
            accs[i] = SimAggregate.newAccumulator(4);
        }
        for (int s = 0; s < 100; s++) {
            SeasonSimulator.LeagueOutcome outcome =
                    SeasonSimulator.simulate(ctx, rnd).get(0);
            assertArrayEquals(realPoints, outcome.points);
            assertArrayEquals(realRank, outcome.ranks);
            for (int i = 0; i < 4; i++) {
                SimAggregate.record(accs[i], outcome.points[i], outcome.ranks[i],
                        4, 2, 1);
            }
        }
        SimAggregate.Stats statsB = SimAggregate.stats(accs[1]);
        assertEquals(100.0, statsB.nSims, 0.0);
        assertEquals(1.0, statsB.pChampion, 0.0);   // B is the certain champion
        assertEquals(0.0, statsB.sdPoints, 0.0);
        SimAggregate.Stats statsD = SimAggregate.stats(accs[3]);
        assertEquals(1.0, statsD.pRelegated, 0.0);  // D is certainly relegated
        assertEquals(0.0, statsD.pTop, 0.0);
        assertEquals(1.0, statsB.rankHist[0], 0.0); // all 100 sims at rank 1
    }

    @Test
    public void strongTeamWinsTitleWithHighProbabilityWhenNothingPlayed() {
        // nothing played, full double round-robin to sample; A's lambdas
        // dominate and D's are hopeless -> A should take most titles and
        // occupy the top, D the bottom
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        int[][] pairs = {{0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}};
        // per-team attacking lambdas: {home lambda, away lambda}
        double[][] lambdas = {
                {3.5, 3.0},   // A: dominant
                {1.4, 1.1},   // B
                {1.5, 1.2},   // C
                {0.25, 0.2}   // D: hopeless
        };
        for (int[] p : pairs) {
            for (int leg = 0; leg < 2; leg++) { // home and away leg
                int h = leg == 0 ? p[0] : p[1];
                int a = leg == 0 ? p[1] : p[0];
                fixtures.add(new SimContext.Fixture("Test League", h, a,
                        lambdas[h][0], lambdas[a][1],
                        20700L + fixtures.size()));
            }
        }
        SimContext ctx = new SimContext(
                Arrays.asList(league(1, 2, new int[]{0, 0, 0, 0},
                        new int[]{12, 8, 6, 1}, new int[]{1, 2, 3, 4})),
                fixtures);
        Random rnd = new Random(9L);
        int nSims = 1000;
        double[] accA = SimAggregate.newAccumulator(4);
        double[] accD = SimAggregate.newAccumulator(4);
        for (int s = 0; s < nSims; s++) {
            SeasonSimulator.LeagueOutcome outcome =
                    SeasonSimulator.simulate(ctx, rnd).get(0);
            SimAggregate.record(accA, outcome.points[0], outcome.ranks[0], 4, 2, 1);
            SimAggregate.record(accD, outcome.points[3], outcome.ranks[3], 4, 2, 1);
        }
        SimAggregate.Stats statsA = SimAggregate.stats(accA);
        SimAggregate.Stats statsD = SimAggregate.stats(accD);
        assertEquals(nSims, statsA.nSims, 0.0);
        assertTrue("dominant team should win most titles, got "
                + statsA.pChampion, statsA.pChampion > 0.9);
        assertTrue(statsA.pTop > 0.95);
        assertTrue("hopeless team should be relegated most of the time, got "
                + statsD.pRelegated, statsD.pRelegated > 0.9);
        assertTrue(statsA.avgRank < statsD.avgRank);
        assertTrue(statsA.avgPoints > statsD.avgPoints);
        // champion probability is the top rank histogram bin
        assertEquals(statsA.pChampion, statsA.rankHist[0], 0.0);
        // the four league-place probabilities normalise to 1
        double histSum = 0.0;
        for (double p : statsA.rankHist) {
            histSum += p;
        }
        assertEquals(1.0, histSum, 1e-9);
    }

    @Test
    public void midSeasonSnapshotCombinesRealAndSampledResults() {
        // 2 real matches played (A beat B 2-0 home, D lost 0-3 at C),
        // 2 fixtures remaining; standings must start from 3/0/3/0 points
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        fixtures.add(new SimContext.Fixture("Test League", 1, 2, 1.0, 1.0, 20700L));
        fixtures.add(new SimContext.Fixture("Test League", 3, 0, 0.5, 2.5, 20701L));
        SimContext ctx = new SimContext(
                Arrays.asList(league(1, 2, new int[]{3, 0, 3, 0},
                        new int[]{6, 1, 7, 1}, new int[]{2, 3, 1, 4})),
                fixtures);
        Random rnd = new Random(21L);
        for (int s = 0; s < 500; s++) {
            SeasonSimulator.LeagueOutcome outcome =
                    SeasonSimulator.simulate(ctx, rnd).get(0);
            // exactly 6 matches per season: 2 real + 2 per remaining fixture
            // ... each fixture played once -> total goals tracked on each row
            assertTrue(outcome.points[0] >= 3); // real win kept
            assertTrue(outcome.points[3] >= 0);
            for (int rank : outcome.ranks) {
                assertTrue(rank >= 1 && rank <= 4);
            }
        }
    }

    @Test
    public void sameSeedReproducesIdenticalSeasons() {
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        fixtures.add(new SimContext.Fixture("Test League", 0, 1, 2.0, 0.5, 20700L));
        fixtures.add(new SimContext.Fixture("Test League", 2, 3, 1.2, 1.1, 20701L));
        SimContext ctx = new SimContext(
                Arrays.asList(league(1, 2, new int[]{1, 4, 2, 0},
                        new int[]{4, 7, 5, 1}, new int[]{3, 1, 2, 4})),
                fixtures);
        List<SeasonSimulator.LeagueOutcome> first =
                SeasonSimulator.simulate(ctx, new Random(77L));
        List<SeasonSimulator.LeagueOutcome> second =
                SeasonSimulator.simulate(ctx, new Random(77L));
        assertArrayEquals(first.get(0).points, second.get(0).points);
        assertArrayEquals(first.get(0).ranks, second.get(0).ranks);
    }

    @Test
    public void aggregateMathMatchesHandComputedValues() {
        // 4 teams, topPlaces=2, relegationPlaces=1
        double[] acc = SimAggregate.newAccumulator(4);
        // three seasons with known outcomes for team A:
        SimAggregate.record(acc, 90, 1, 4, 2, 1); // champion
        SimAggregate.record(acc, 70, 2, 4, 2, 1); // runner-up
        SimAggregate.record(acc, 80, 1, 4, 2, 1); // champion again
        SimAggregate.Stats stats = SimAggregate.stats(acc);
        assertEquals(3.0, stats.nSims, 0.0);
        assertEquals(80.0, stats.avgPoints, 1e-12);
        // sd of {90, 70, 80} = sqrt(200/3)
        assertEquals(Math.sqrt(200.0 / 3.0), stats.sdPoints, 1e-12);
        assertEquals((1 + 2 + 1) / 3.0, stats.avgRank, 1e-12);
        assertEquals(2.0 / 3.0, stats.pChampion, 1e-12);
        assertEquals(1.0, stats.pTop, 1e-12);
        assertEquals(0.0, stats.pRelegated, 1e-12);
        assertArrayEquals(new double[]{2.0 / 3.0, 1.0 / 3.0, 0.0, 0.0},
                stats.rankHist, 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyAccumulatorRejectedByStats() {
        SimAggregate.stats(new double[SimAggregate.HEAD + 4]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rankOutsideLeagueSizeRejected() {
        double[] acc = SimAggregate.newAccumulator(4);
        SimAggregate.record(acc, 10, 5, 4, 2, 1);
    }
}
