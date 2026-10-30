package com.footpred.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Broadcast payload for Job P5.2 (Monte-Carlo season simulation).
 *
 * Carries everything a mapper needs to roll out complete seasons without
 * any external dependency:
 *
 *   - league roster with the standing snapshot at the replay cutoff date
 *     (points / goals already accumulated from real matches),
 *   - remaining fixtures with the DC Poisson intensities pre-computed by
 *     the driver (mappers need no model),
 *   - the actual final table (reduce side appends real rank/points as
 *     side-by-side comparison columns).
 *
 * Encoded as '\n'-separated TSV lines with a leading tag; the blob rides
 * through the job configuration like the DC parameter broadcast during
 * training. Decimal fields use Double.toString, which round-trips exactly.
 *
 * Line shapes:
 *
 *   L \t leagueOrdinal \t name \t relegationPlaces \t topPlaces
 *   T \t leagueOrdinal \t teamName                  (order = local index)
 *   P \t leagueOrdinal \t localIdx \t points \t gf \t ga \t played
 *   R \t leagueOrdinal \t localIdx \t realPoints \t realRank
 *   F \t leagueOrdinal \t homeLocalIdx \t awayLocalIdx \t lambdaH \t lambdaA \t epochDay
 */
public final class SimContext {

    /** One league inside the simulation context (arrays indexed by local idx). */
    public static final class League {
        public final String name;
        public final int relegationPlaces;
        public final int topPlaces;
        public final int nTeams;
        public final List<String> teamNames;
        /** Standing snapshot at the cutoff date (real played matches). */
        public final int[] points;
        public final int[] goalsFor;
        public final int[] goalsAgainst;
        public final int[] played;
        /** Actual final table of the season (replay ground truth). */
        public final int[] realPoints;
        public final int[] realRank;

        public League(String name, int relegationPlaces, int topPlaces,
                      List<String> teamNames, int[] points, int[] goalsFor,
                      int[] goalsAgainst, int[] played, int[] realPoints,
                      int[] realRank) {
            this.name = name;
            this.relegationPlaces = relegationPlaces;
            this.topPlaces = topPlaces;
            this.nTeams = teamNames.size();
            this.teamNames = new ArrayList<String>(teamNames);
            this.points = points.clone();
            this.goalsFor = goalsFor.clone();
            this.goalsAgainst = goalsAgainst.clone();
            this.played = played.clone();
            this.realPoints = realPoints.clone();
            this.realRank = realRank.clone();
        }
    }

    /** One remaining fixture (at or after the cutoff date). */
    public static final class Fixture {
        public final String league;
        public final int homeIdx;
        public final int awayIdx;
        public final double lambdaHome;
        public final double lambdaAway;
        public final long epochDay;

        public Fixture(String league, int homeIdx, int awayIdx,
                       double lambdaHome, double lambdaAway, long epochDay) {
            this.league = league;
            this.homeIdx = homeIdx;
            this.awayIdx = awayIdx;
            this.lambdaHome = lambdaHome;
            this.lambdaAway = lambdaAway;
            this.epochDay = epochDay;
        }
    }

    public final List<League> leagues;
    /** All remaining fixtures, ordered by epochDay (natural rollout order). */

    public final List<Fixture> fixtures;

    public SimContext(List<League> leagues, List<Fixture> fixtures) {
        this.leagues = new ArrayList<League>(leagues);
        this.fixtures = new ArrayList<Fixture>(fixtures);
    }

    /** League lookup by name, or null when absent. */
    public League leagueByName(String name) {
        for (League league : leagues) {
            if (league.name.equals(name)) {
                return league;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Encoding                                                          //
    // ------------------------------------------------------------------ //

    public String encode() {
        Map<String, Integer> ordinalByLeague = new LinkedHashMap<String, Integer>();
        StringBuilder sb = new StringBuilder();
        for (int o = 0; o < leagues.size(); o++) {
            League league = leagues.get(o);
            ordinalByLeague.put(league.name, o);
            sb.append("L\t").append(o).append('\t').append(league.name)
                    .append('\t').append(league.relegationPlaces)
                    .append('\t').append(league.topPlaces).append('\n');
        }
        for (League league : leagues) {
            int o = ordinalByLeague.get(league.name);
            for (String team : league.teamNames) {
                sb.append("T\t").append(o).append('\t').append(team).append('\n');
            }
        }
        for (League league : leagues) {
            int o = ordinalByLeague.get(league.name);
            for (int i = 0; i < league.nTeams; i++) {
                sb.append("P\t").append(o).append('\t').append(i)
                        .append('\t').append(league.points[i])
                        .append('\t').append(league.goalsFor[i])
                        .append('\t').append(league.goalsAgainst[i])
                        .append('\t').append(league.played[i]).append('\n');
            }
        }
        for (League league : leagues) {
            int o = ordinalByLeague.get(league.name);
            for (int i = 0; i < league.nTeams; i++) {
                sb.append("R\t").append(o).append('\t').append(i)
                        .append('\t').append(league.realPoints[i])
                        .append('\t').append(league.realRank[i]).append('\n');
            }
        }
        for (Fixture f : fixtures) {
            sb.append("F\t").append(ordinalByLeague.get(f.league))
                    .append('\t').append(f.homeIdx).append('\t').append(f.awayIdx)
                    .append('\t').append(Double.toString(f.lambdaHome))
                    .append('\t').append(Double.toString(f.lambdaAway))
                    .append('\t').append(f.epochDay).append('\n');
        }
        return sb.toString();
    }

    public static SimContext decode(String blob) throws IOException {
        if (blob == null || blob.isEmpty()) {
            throw new IOException("empty sim.context blob");
        }
        // pass 1: league headers and rosters (must precede counters)
        Map<Integer, Builder> builders = new LinkedHashMap<Integer, Builder>();
        for (String line : blob.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            String tag = f[0];
            if ("L".equals(tag)) {
                if (f.length != 5) {
                    throw badLine(line);
                }
                int o = Integer.parseInt(f[1]);
                Builder b = builders.get(o);
                if (b != null) {
                    throw new IOException("duplicate league ordinal: " + o);
                }
                b = new Builder();
                b.name = f[2];
                b.relegationPlaces = Integer.parseInt(f[3]);
                b.topPlaces = Integer.parseInt(f[4]);
                builders.put(o, b);
            } else if ("T".equals(tag)) {
                if (f.length != 3) {
                    throw badLine(line);
                }
                Builder b = requireLeague(builders, f[1], line);
                b.teamNames.add(f[2]);
            } else if (!"P".equals(tag) && !"R".equals(tag) && !"F".equals(tag)) {
                throw badLine(line); // unknown tag (P/R/F are handled in pass 2)
            }
        }
        // pass 2: counters and fixtures (roster must be complete by now)
        List<Fixture> fixtures = new ArrayList<Fixture>();
        for (String line : blob.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            String tag = f[0];
            if ("P".equals(tag)) {
                if (f.length != 7) {
                    throw badLine(line);
                }
                Builder b = requireLeague(builders, f[1], line);
                b.allocate();
                int i = Integer.parseInt(f[2]);
                checkRange(i, b.teamNames.size(), line);
                b.points[i] = Integer.parseInt(f[3]);
                b.goalsFor[i] = Integer.parseInt(f[4]);
                b.goalsAgainst[i] = Integer.parseInt(f[5]);
                b.played[i] = Integer.parseInt(f[6]);
            } else if ("R".equals(tag)) {
                if (f.length != 5) {
                    throw badLine(line);
                }
                Builder b = requireLeague(builders, f[1], line);
                b.allocate();
                int i = Integer.parseInt(f[2]);
                checkRange(i, b.teamNames.size(), line);
                b.realPoints[i] = Integer.parseInt(f[3]);
                b.realRank[i] = Integer.parseInt(f[4]);
            } else if ("F".equals(tag)) {
                if (f.length != 7) {
                    throw badLine(line);
                }
                Builder b = requireLeague(builders, f[1], line);
                if (b.teamNames.size() < 2) {
                    throw badLine(line);
                }
                int home = Integer.parseInt(f[2]);
                int away = Integer.parseInt(f[3]);
                checkRange(home, b.teamNames.size(), line);
                checkRange(away, b.teamNames.size(), line);
                fixtures.add(new Fixture(b.name, home, away,
                        Double.parseDouble(f[4]), Double.parseDouble(f[5]),
                        Long.parseLong(f[6])));
            }
        }
        List<League> leagues = new ArrayList<League>(builders.size());
        for (Builder b : builders.values()) {
            if (b.teamNames.size() < 2 || b.points == null || b.realRank == null) {
                throw new IOException(
                        "incomplete league in sim.context: " + b.name);
            }
            leagues.add(new League(b.name, b.relegationPlaces, b.topPlaces,
                    b.teamNames, b.points, b.goalsFor, b.goalsAgainst,
                    b.played, b.realPoints, b.realRank));
        }
        return new SimContext(leagues, fixtures);
    }

    private static final class Builder {
        String name;
        int relegationPlaces;
        int topPlaces;
        final List<String> teamNames = new ArrayList<String>();
        int[] points;
        int[] goalsFor;
        int[] goalsAgainst;
        int[] played;
        int[] realPoints;
        int[] realRank;

        void allocate() {
            int n = teamNames.size();
            if (points == null) {
                points = new int[n];
                goalsFor = new int[n];
                goalsAgainst = new int[n];
                played = new int[n];
                realPoints = new int[n];
                realRank = new int[n];
            }
        }
    }

    private static Builder requireLeague(Map<Integer, Builder> builders,
                                         String ordinal, String line) throws IOException {
        int o = Integer.parseInt(ordinal);
        Builder b = builders.get(o);
        if (b == null) {
            throw new IOException("unknown league ordinal in sim.context line: " + line);
        }
        return b;
    }

    private static void checkRange(int idx, int n, String line) throws IOException {
        if (idx < 0 || idx >= n) {
            throw new IOException("index out of range in sim.context line: " + line);
        }
    }

    private static IOException badLine(String line) {
        return new IOException("malformed sim.context line: " + line);
    }
}
