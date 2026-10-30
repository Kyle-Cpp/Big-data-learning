package com.footpred.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * League standings accumulator with the tie-break ordering used throughout
 * the Monte-Carlo season simulation (Job P5.2):
 *
 *   1. points (win 3 / draw 1 / loss 0)
 *   2. goal difference, descending
 *   3. goals for, descending
 *   4. team name ascending (stable, deterministic final resort)
 *
 * (Head-to-head tie-breaks as used by some leagues are intentionally
 * simplified to the goal-difference convention; the ordering is documented
 * in the README contract.)
 */
public final class LeagueTable {

    public final String league;
    public final List<String> teamNames;
    public final int nTeams;
    public final int[] points;
    public final int[] goalsFor;
    public final int[] goalsAgainst;
    public final int[] played;

    public LeagueTable(String league, List<String> teamNames) {
        if (teamNames == null || teamNames.size() < 2) {
            throw new IllegalArgumentException(
                    "league " + league + " needs at least 2 teams");
        }
        this.league = league;
        this.teamNames = new ArrayList<String>(teamNames);
        this.nTeams = this.teamNames.size();
        this.points = new int[nTeams];
        this.goalsFor = new int[nTeams];
        this.goalsAgainst = new int[nTeams];
        this.played = new int[nTeams];
    }

    /** Applies one played match to the standings (local team indices). */
    public void applyResult(int homeIdx, int awayIdx, int homeGoals, int awayGoals) {
        checkIndex(homeIdx);
        checkIndex(awayIdx);
        played[homeIdx]++;
        played[awayIdx]++;
        goalsFor[homeIdx] += homeGoals;
        goalsAgainst[homeIdx] += awayGoals;
        goalsFor[awayIdx] += awayGoals;
        goalsAgainst[awayIdx] += homeGoals;
        if (homeGoals > awayGoals) {
            points[homeIdx] += 3;
        } else if (homeGoals < awayGoals) {
            points[awayIdx] += 3;
        } else {
            points[homeIdx]++;
            points[awayIdx]++;
        }
    }

    /** Standings snapshot with copied counters (team names shared, read-only). */
    public LeagueTable copyStandings() {
        LeagueTable copy = new LeagueTable(league, teamNames);
        System.arraycopy(points, 0, copy.points, 0, nTeams);
        System.arraycopy(goalsFor, 0, copy.goalsFor, 0, nTeams);
        System.arraycopy(goalsAgainst, 0, copy.goalsAgainst, 0, nTeams);
        System.arraycopy(played, 0, copy.played, 0, nTeams);
        return copy;
    }

    /**
     * 1-based ranks under the documented tie-break ordering; index i of the
     * returned array is the rank of teamNames.get(i).
     */
    public int[] ranks() {
        Integer[] order = new Integer[nTeams];
        for (int i = 0; i < nTeams; i++) {
            order[i] = i;
        }
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                if (points[a] != points[b]) {
                    return Integer.compare(points[b], points[a]);
                }
                int gda = goalsFor[a] - goalsAgainst[a];
                int gdb = goalsFor[b] - goalsAgainst[b];
                if (gda != gdb) {
                    return Integer.compare(gdb, gda);
                }
                if (goalsFor[a] != goalsFor[b]) {
                    return Integer.compare(goalsFor[b], goalsFor[a]);
                }
                return teamNames.get(a).compareTo(teamNames.get(b));
            }
        });
        int[] ranks = new int[nTeams];
        for (int pos = 0; pos < nTeams; pos++) {
            ranks[order[pos]] = pos + 1;
        }
        return ranks;
    }

    private void checkIndex(int idx) {
        if (idx < 0 || idx >= nTeams) {
            throw new IndexOutOfBoundsException(
                    "team index " + idx + " outside 0.." + (nTeams - 1));
        }
    }
}
