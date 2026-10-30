package com.footpred.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One Monte-Carlo season rollout - the map-side simulation engine of
 * Job P5.2.
 *
 * Starts from each league's standing snapshot at the replay cutoff date
 * (real results), samples independent Poisson score lines for every
 * remaining fixture with the pre-computed DC intensities and ranks the
 * final tables under the documented tie-break ordering.
 *
 * Stateless by design: the full simulation context is supplied per call,
 * so any mapper can roll out any number of seasons from the same payload,
 * and unit tests drive it offline without MapReduce.
 */
public final class SeasonSimulator {

    private SeasonSimulator() {
    }

    /**
     * Rolls out one complete season for every league in the context.
     * Consumes 2 Poisson draws per remaining fixture from {@code rnd};
     * the returned outcomes are fully determined by the context and the
     * Random state (reproducible replays).
     */
    public static List<LeagueOutcome> simulate(SimContext ctx, Random rnd) {
        Map<String, LeagueTable> tables = new HashMap<String, LeagueTable>();
        List<LeagueTable> ordered = new ArrayList<LeagueTable>(ctx.leagues.size());
        for (SimContext.League league : ctx.leagues) {
            LeagueTable table = new LeagueTable(league.name, league.teamNames);
            System.arraycopy(league.points, 0, table.points, 0, league.nTeams);
            System.arraycopy(league.goalsFor, 0, table.goalsFor, 0, league.nTeams);
            System.arraycopy(league.goalsAgainst, 0, table.goalsAgainst, 0, league.nTeams);
            System.arraycopy(league.played, 0, table.played, 0, league.nTeams);
            tables.put(league.name, table);
            ordered.add(table);
        }
        for (SimContext.Fixture fixture : ctx.fixtures) {
            LeagueTable table = tables.get(fixture.league);
            if (table == null) {
                throw new IllegalStateException(
                        "fixture references unknown league: " + fixture.league);
            }
            int homeGoals = PoissonSampler.sample(rnd, fixture.lambdaHome);
            int awayGoals = PoissonSampler.sample(rnd, fixture.lambdaAway);
            table.applyResult(fixture.homeIdx, fixture.awayIdx, homeGoals, awayGoals);
        }
        List<LeagueOutcome> outcomes =
                new ArrayList<LeagueOutcome>(ordered.size());
        for (int o = 0; o < ordered.size(); o++) {
            LeagueTable table = ordered.get(o);
            outcomes.add(new LeagueOutcome(o, table.points.clone(), table.ranks()));
        }
        return outcomes;
    }

    /** Final points and 1-based rank of every team of one league after a rollout. */
    public static final class LeagueOutcome {
        /** Position of the league inside {@code ctx.leagues}. */
        public final int ordinal;
        public final int[] points;
        public final int[] ranks;

        LeagueOutcome(int ordinal, int[] points, int[] ranks) {
            this.ordinal = ordinal;
            this.points = points;
            this.ranks = ranks;
        }
    }
}
