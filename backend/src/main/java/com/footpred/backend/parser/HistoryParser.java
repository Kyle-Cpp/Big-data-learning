package com.footpred.backend.parser;

import com.footpred.backend.domain.HistoryRow;
import com.footpred.backend.domain.HistoryRow.HistoryKind;
import com.footpred.backend.domain.LeagueCode;
import com.footpred.backend.domain.SeasonCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the eight historical analyses into {@link HistoryRow}.
 *
 * <p>Four different shapes are handled, because the two packages express the
 * same idea of "a history table" very differently:
 * <ul>
 *   <li><b>long</b> (P6.1, P6.2): {@code league|season} key, then a fixed
 *       column list;</li>
 *   <li><b>bucketed</b> (P6.3, P6.7): one row per bucket, already aggregated;</li>
 *   <li><b>tagged</b> (P6.8): a leading {@code D}/{@code S} record type marks
 *       detail versus season summary rows;</li>
 *   <li><b>per-entity</b> (P6.4, P6.5, P6.6): one row per team pair, player or
 *       league-season.</li>
 * </ul>
 * Only the fields meaningful for a given kind are populated; the rest stay null
 * so a caller cannot accidentally read a count as a probability.
 */
public final class HistoryParser {

    private HistoryParser() {
    }

    // ---------------------------------------------------------------- P6.1

    /**
     * {@code league|season <TAB> league <TAB> season <TAB> champion <TAB> points
     * <TAB> wins <TAB> draws <TAB> losses <TAB> goalsFor <TAB> goalsAgainst
     * <TAB> goalDiff <TAB> matches <TAB> teams}.
     */
    public static List<HistoryRow> parseChampions(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            // Value: league season champion points wins draws losses goalsFor
            // goalsAgainst goalDiff matches teams.
            String[] f = Tsv.valueFields(line, 12);
            if (f.length < 12) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.CHAMPION_EVOLUTION;
            row.league = LeagueCode.canonical(f[0]);
            row.season = SeasonCode.canonical(f[1]);
            row.team = f[2];
            row.value = Tsv.toDouble(f[7], Double.NaN);          // goals for
            row.secondaryValue = Tsv.toDouble(f[8], Double.NaN); // goals against
            row.count = (long) Tsv.toDouble(f[10], 0d);          // matches played
            result.add(row);
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.2

    /**
     * {@code league|season <TAB> league <TAB> season <TAB> matches <TAB> homeWins
     * <TAB> draws <TAB> awayWins <TAB> homeWinRate <TAB> drawRate <TAB>
     * awayWinRate <TAB> avgHomeGoals <TAB> avgAwayGoals <TAB> winRateGap
     * <TAB> goalGap}.
     */
    public static List<HistoryRow> parseHomeAdvantage(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            // Value: league season matches homeWins draws awayWins homeWinRate
            // drawRate awayWinRate avgHomeGoals avgAwayGoals winRateGap goalGap.
            String[] f = Tsv.valueFields(line, 13);
            if (f.length < 13) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.HOME_ADVANTAGE;
            row.league = LeagueCode.canonical(f[0]);
            row.season = SeasonCode.canonical(f[1]);
            row.count = (long) Tsv.toDouble(f[2], 0d);
            row.value = Tsv.toDouble(f[6], Double.NaN);          // home win rate
            row.secondaryValue = Tsv.toDouble(f[8], Double.NaN); // away win rate
            result.add(row);
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.3

    /**
     * {@code competition <TAB> season <TAB> bucket <TAB> count <TAB> share
     * <TAB> total}, emitted once per bucket in a fixed order.
     */
    public static List<HistoryRow> parseGoalMinutes(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            // Value: competition season bucket count share total. The reducer key
            // is competition|season and is already dropped by valueFields().
            String[] f = Tsv.valueFields(line, 6);
            if (f.length < 6) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.GOAL_MINUTES;
            row.league = LeagueCode.canonical(f[0]);
            row.season = SeasonCode.canonical(f[1]);
            row.dimension = f[2];
            row.count = (long) Tsv.toDouble(f[3], 0d);
            row.value = Tsv.toDouble(f[4], Double.NaN);
            result.add(row);
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.4

    /**
     * {@code league|teamA|teamB <TAB> league <TAB> teamA <TAB> teamB <TAB>
     * matches <TAB> winsA <TAB> draws <TAB> winsB <TAB> goalsA <TAB> goalsB
     * <TAB> winRateA <TAB> drawRate <TAB> winRateB <TAB> lastDate <TAB>
     * recentForm}.
     */
    public static List<HistoryRow> parseHeadToHead(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            // Value: league teamA teamB matches winsA draws winsB goalsA goalsB
            // winRateA drawRate winRateB lastDate recentForm.
            String[] f = Tsv.valueFields(line, 14);
            if (f.length < 14) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.HEAD_TO_HEAD;
            row.league = LeagueCode.canonical(f[0]);
            row.team = f[1] + " vs " + f[2];
            row.count = (long) Tsv.toDouble(f[3], 0d);
            row.value = Tsv.toDouble(f[9], Double.NaN);          // team A win rate
            row.secondaryValue = Tsv.toDouble(f[11], Double.NaN); // team B win rate
            result.add(row);
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.5

    /**
     * Career rows carry a composite key {@code player <TAB> source <TAB> season}
     * followed by four integer sums: {@code apps goals pens ogs}.
     */
    public static List<HistoryRow> parseCareerCurve(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 7) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.CAREER_CURVE;
            row.player = f[0];
            row.dimension = f[1];                       // wc | intl
            row.season = SeasonCode.canonical(f[2]);
            row.count = (long) Tsv.toDouble(f[3], 0d);  // appearances
            row.value = Tsv.toDouble(f[4], Double.NaN); // goals
            row.secondaryValue = Tsv.toDouble(f[5], Double.NaN); // penalties
            result.add(row);
        }
        return result;
    }

    /**
     * Messi club-season rows: {@code league <TAB> season <TAB> apps <TAB> shots
     * <TAB> goals <TAB> xG <TAB> assists <TAB> penGoals}.
     *
     * <p>Distinguished from {@link #parseCareerCurve} by column count and by the
     * decimal xG in position 5, which the career rows never contain.
     */
    public static List<HistoryRow> parseCareerClub(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 8) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.CAREER_CURVE;
            row.league = LeagueCode.canonical(f[0]);
            row.season = SeasonCode.canonical(f[1]);
            row.dimension = "club";
            row.count = (long) Tsv.toDouble(f[2], 0d);   // appearances
            row.value = Tsv.toDouble(f[4], Double.NaN);  // goals
            row.secondaryValue = Tsv.toDouble(f[5], Double.NaN); // xG
            result.add(row);
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.6

    /**
     * {@code league <TAB> season <TAB> nGames <TAB> goalsPG <TAB> halfGoalsPG
     * <TAB> shotsPG <TAB> sotPG <TAB> foulsPG <TAB> cornersPG <TAB> yellowsPG
     * <TAB> redsPG}.
     *
     * <p>Empty metric fields are legal and mean "not recorded for that season";
     * they are parsed as NaN rather than zero.
     */
    public static List<HistoryRow> parseLeagueStyle(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 11) {
                continue;
            }
            HistoryRow base = new HistoryRow();
            base.kind = HistoryKind.LEAGUE_STYLE;
            base.league = LeagueCode.canonical(f[0]);
            base.season = SeasonCode.canonical(f[1]);
            base.count = (long) Tsv.toDouble(f[2], 0d);

            String[] metrics = {"goalsPerGame", "halfTimeGoalsPerGame", "shotsPerGame",
                    "shotsOnTargetPerGame", "foulsPerGame", "cornersPerGame",
                    "yellowsPerGame", "redsPerGame"};
            for (int i = 0; i < metrics.length; i++) {
                HistoryRow row = copyOf(base);
                row.dimension = metrics[i];
                row.value = Tsv.toDouble(f[3 + i], Double.NaN);
                result.add(row);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.7

    /**
     * {@code source <TAB> decade <TAB> bucket <TAB> count <TAB> pct}.
     * The bucket is the home-minus-away margin clamped to -5..5.
     */
    public static List<HistoryRow> parseScoreDifference(List<String> lines) {
        List<HistoryRow> result = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 5) {
                continue;
            }
            HistoryRow row = new HistoryRow();
            row.kind = HistoryKind.SCORE_DIFFERENCE;
            row.league = f[0];                       // intl | league
            row.season = f[1];                       // decade label
            row.dimension = f[2];                    // margin bucket
            row.count = (long) Tsv.toDouble(f[3], 0d);
            row.value = Tsv.toDouble(f[4], Double.NaN);
            result.add(row);
        }
        return result;
    }

    // ---------------------------------------------------------------- P6.8

    /**
     * Two record types share one file:
     * {@code D <TAB> league <TAB> season <TAB> country <TAB> squadPlayers
     * <TAB> playedPlayers} and
     * {@code S <TAB> league <TAB> season <TAB> nCountries <TAB> squadPlayers
     * <TAB> hhi <TAB> shannon <TAB> foreignShare}.
     *
     * <p>The summary row is the one a chart needs, so detail rows are folded into
     * it: {@code dimension} records the country and the two counts ride along in
     * {@code count} and {@code secondaryValue} so nothing is lost.
     */
    public static List<HistoryRow> parseNationality(List<String> lines) {
        List<HistoryRow> detail = new ArrayList<HistoryRow>();
        List<HistoryRow> summary = new ArrayList<HistoryRow>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 6) {
                continue;
            }
            String type = f[0];
            if ("D".equals(type) && f.length >= 6) {
                HistoryRow row = new HistoryRow();
                row.kind = HistoryKind.NATIONALITY;
                row.league = LeagueCode.canonical(f[1]);
                row.season = SeasonCode.canonical(f[2]);
                row.dimension = f[3];
                row.count = (long) Tsv.toDouble(f[4], 0d);
                row.secondaryValue = Tsv.toDouble(f[5], 0d);
                detail.add(row);
            } else if ("S".equals(type) && f.length >= 8) {
                HistoryRow row = new HistoryRow();
                row.kind = HistoryKind.NATIONALITY;
                row.league = LeagueCode.canonical(f[1]);
                row.season = SeasonCode.canonical(f[2]);
                row.dimension = "summary";
                row.count = (long) Tsv.toDouble(f[3], 0d);   // distinct countries
                row.value = Tsv.toDouble(f[7], Double.NaN);  // foreign share
                row.secondaryValue = Tsv.toDouble(f[6], Double.NaN); // shannon
                summary.add(row);
            }
        }
        summary.addAll(detail);
        return summary;
    }

    private static HistoryRow copyOf(HistoryRow source) {
        HistoryRow row = new HistoryRow();
        row.kind = source.kind;
        row.league = source.league;
        row.season = source.season;
        row.team = source.team;
        row.player = source.player;
        row.count = source.count;
        return row;
    }
}
