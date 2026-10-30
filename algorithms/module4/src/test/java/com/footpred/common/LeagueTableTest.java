package com.footpred.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LeagueTableTest {

    private static final List<String> TEAMS =
            Arrays.asList("Alpha", "Beta", "Gamma", "Delta");

    @Test
    public void pointsFollowWinDrawLossConvention() {
        LeagueTable t = new LeagueTable("L1", TEAMS);
        t.applyResult(0, 1, 2, 0); // Alpha beats Beta
        t.applyResult(2, 3, 1, 1); // Gamma draws Delta
        assertEquals(3, t.points[0]);
        assertEquals(0, t.points[1]);
        assertEquals(1, t.points[2]);
        assertEquals(1, t.points[3]);
        assertEquals(1, t.played[0]);
        assertEquals(1, t.played[1]);
    }

    @Test
    public void goalsAndGoalDifferenceAccumulate() {
        LeagueTable t = new LeagueTable("L1", TEAMS);
        t.applyResult(0, 1, 5, 2);
        t.applyResult(0, 2, 0, 3);
        assertEquals(2, t.played[0]);
        assertEquals(5, t.goalsFor[0]);
        assertEquals(5, t.goalsAgainst[0]);
        assertEquals(2, t.goalsFor[1]);
        assertEquals(5, t.goalsAgainst[1]);
        assertEquals(3, t.goalsFor[2]);
        assertEquals(0, t.goalsAgainst[2]);
    }

    @Test
    public void rankingBreaksTiesByGoalDiffThenGoalsThenName() {
        // Alpha vs Delta twice 2-1: Alpha 6 pts, GF 4, GD +2
        // Gamma vs Delta twice 3-2 and 2-1: Gamma 6 pts, GF 5, GD +2
        //   -> same points, same GD, Gamma first by goals for
        // Beta vs Delta 1-0 and Delta vs Beta 1-0: both 3 pts, GF 1, GD 0
        //   -> fully tied, Beta third by team name asc, Delta fourth
        LeagueTable t = new LeagueTable("L1", TEAMS);
        t.applyResult(0, 3, 2, 1); // Alpha beats Delta
        t.applyResult(0, 3, 2, 1); // Alpha beats Delta again
        t.applyResult(2, 3, 3, 2); // Gamma beats Delta
        t.applyResult(2, 3, 2, 1); // Gamma beats Delta again
        t.applyResult(1, 3, 1, 0); // Beta beats Delta
        t.applyResult(3, 1, 1, 0); // Delta beats Beta
        int[] ranks = t.ranks();
        assertEquals(1, ranks[2]); // Gamma: 6 pts, GF 5
        assertEquals(2, ranks[0]); // Alpha: 6 pts, GF 4
        assertEquals(3, ranks[1]); // Beta: 3 pts, fully tied with Delta
        assertEquals(4, ranks[3]); // Delta: name resort behind Beta
    }

    @Test
    public void ranksAreAPermutationOfOneToN() {
        LeagueTable t = new LeagueTable("L1", TEAMS);
        t.applyResult(0, 1, 1, 1);
        t.applyResult(2, 3, 2, 0);
        int[] ranks = t.ranks();
        int[] sorted = ranks.clone();
        Arrays.sort(sorted);
        assertArrayEquals(new int[]{1, 2, 3, 4}, sorted);
    }

    @Test
    public void snapshotCopyIsIndependent() {
        LeagueTable t = new LeagueTable("L1", TEAMS);
        t.applyResult(0, 1, 3, 0);
        LeagueTable copy = t.copyStandings();
        copy.applyResult(2, 3, 1, 0);
        assertEquals(0, t.points[2]);
        assertEquals(3, copy.points[2]);
        assertEquals(0, t.played[2]);   // Gamma never played in the original
        assertEquals(1, copy.played[2]); // ... but one match in the snapshot
        // shared read-only roster
        assertEquals(t.teamNames, copy.teamNames);
    }

    @Test(expected = IllegalArgumentException.class)
    public void leagueNeedsAtLeastTwoTeams() {
        new LeagueTable("L1", Arrays.asList("Only"));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void outOfRangeTeamIndexRejected() {
        LeagueTable t = new LeagueTable("L1", TEAMS);
        t.applyResult(0, 9, 1, 0);
    }
}
