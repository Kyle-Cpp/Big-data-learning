package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import com.footpred.common.VectorWritable;

/**
 * Job P6.5a (history p65, step 1): player career curves from the CSV sources.
 *
 * Three input files, distinguished by file name in setup():
 *   player_appearances.csv (Fjelstul WC package) -> per-match World Cup
 *     appearances, season key "WC-<year>"
 *   goals.csv (Fjelstul WC package) -> per-goal World Cup records incl.
 *     own-goal / penalty flags
 *   goalscorers.csv (martj42) -> per-goal international records for every
 *     scorer since 1916, season key = calendar year
 *
 * Emitted key = player \t source \t season, value = [apps, goals, pens, ogs]
 * (source: wc | intl). Player IDs are not unified across the packages - the
 * worldcup rows key by display name (given + family), goalscorers rows by the
 * scorer string - so the outputs are per-source and the frontend joins the
 * few showcase players (e.g. "Lionel Messi") by name.
 */
public class CareerCsvMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        WC_APP_ROWS, WC_GOAL_ROWS, INTL_GOAL_ROWS, BAD_LINE
    }

    public static final int DIM = 4;
    public static final int OFF_APPS = 0;
    public static final int OFF_GOALS = 1;
    public static final int OFF_PENS = 2;
    public static final int OFF_OGS = 3;

    // appearances.csv: key_id,tournament_id,tournament_name,match_id,...,
    //   family_name(14),given_name(15)
    private static final int APP_TOURNAMENT_COL = 2;
    private static final int APP_FAMILY_COL = 14;
    private static final int APP_GIVEN_COL = 15;
    // goals.csv has one extra leading column (goal_id): tournament_name(3),
    // family_name(15), given_name(16), own_goal(25), penalty(26)
    private static final int GOAL_TOURNAMENT_COL = 3;
    private static final int GOAL_FAMILY_COL = 15;
    private static final int GOAL_GIVEN_COL = 16;
    private static final int GOAL_OWN_COL = 25;
    private static final int GOAL_PEN_COL = 26;

    private final Text outKey = new Text();
    private final StringBuilder keyBuf = new StringBuilder(64);
    private String sourceKind; // wc-apps | wc-goals | intl-goals
    private String sourceLabel; // wc | intl

    @Override
    protected void setup(Context context) {
        String name = ((FileSplit) context.getInputSplit()).getPath().getName();
        if (name.startsWith("player_appearances")) {
            sourceKind = "wc-apps";
            sourceLabel = "wc";
        } else if (name.startsWith("goalscorers")) {
            sourceKind = "intl-goals";
            sourceLabel = "intl";
        } else if (name.startsWith("goals")) {
            sourceKind = "wc-goals";
            sourceLabel = "wc";
        } else {
            sourceKind = null;
        }
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        if (sourceKind == null) {
            return; // unrelated file staged into the input dir
        }
        String line = value.toString().trim();
        if (line.isEmpty() || line.startsWith("key_id,") || line.startsWith("date,")) {
            return; // headers
        }
        String[] f = line.split(",");
        double[] v = new double[DIM];
        String player;
        String season;
        switch (sourceKind) {
            case "wc-apps":
                if (!parseWcApps(f, v)) {
                    context.getCounter(Counters.BAD_LINE).increment(1);
                    return;
                }
                player = playerName(f, APP_FAMILY_COL, APP_GIVEN_COL);
                season = wcSeason(f, APP_TOURNAMENT_COL);
                context.getCounter(Counters.WC_APP_ROWS).increment(1);
                break;
            case "wc-goals":
                if (!parseWcGoal(f, v)) {
                    context.getCounter(Counters.BAD_LINE).increment(1);
                    return;
                }
                player = playerName(f, GOAL_FAMILY_COL, GOAL_GIVEN_COL);
                season = wcSeason(f, GOAL_TOURNAMENT_COL);
                context.getCounter(Counters.WC_GOAL_ROWS).increment(1);
                break;
            default:
                if (!parseIntlGoal(f, v)) {
                    context.getCounter(Counters.BAD_LINE).increment(1);
                    return;
                }
                player = f[4].trim();
                season = f[0].trim().substring(0, 4);
                context.getCounter(Counters.INTL_GOAL_ROWS).increment(1);
                break;
        }
        if (player.isEmpty() || season == null) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        keyBuf.setLength(0);
        keyBuf.append(player).append('\t').append(sourceLabel).append('\t').append(season);
        outKey.set(keyBuf.toString());
        context.write(outKey, new VectorWritable(v));
    }

    private static String playerName(String[] f, int familyCol, int givenCol) {
        String given = field(f, givenCol);
        String family = field(f, familyCol);
        if (!given.isEmpty() && !family.isEmpty()) {
            return given + " " + family;
        }
        return family.isEmpty() ? given : family;
    }

    /** "1970 FIFA Men's World Cup" -> "WC-1970" (first 4-digit year). */
    static String wcSeason(String[] f, int tournamentCol) {
        String tournament = field(f, tournamentCol);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(tournament);
        return m.find() ? "WC-" + m.group() : null;
    }

    private static boolean parseWcApps(String[] f, double[] v) {
        if (field(f, APP_TOURNAMENT_COL).isEmpty()
                || playerName(f, APP_FAMILY_COL, APP_GIVEN_COL).isEmpty()) {
            return false;
        }
        v[OFF_APPS] = 1.0;
        return true;
    }

    /** Worldcup goals.csv own_goal/penalty are 0/1 ints (not TRUE/FALSE). */
    static boolean parseWcGoal(String[] f, double[] v) {
        if (field(f, GOAL_TOURNAMENT_COL).isEmpty()
                || playerName(f, GOAL_FAMILY_COL, GOAL_GIVEN_COL).isEmpty()) {
            return false;
        }
        boolean ownGoal = "1".equals(field(f, GOAL_OWN_COL));
        boolean penalty = "1".equals(field(f, GOAL_PEN_COL));
        if (!ownGoal) {
            v[OFF_GOALS] = 1.0;
            if (penalty) {
                v[OFF_PENS] = 1.0;
            }
        } else {
            v[OFF_OGS] = 1.0;
        }
        return true;
    }

    /** goalscorers.csv own_goal/penalty are TRUE/FALSE strings. */
    static boolean parseIntlGoal(String[] f, double[] v) {
        if (f.length < 8 || f[4].trim().isEmpty() || !f[0].trim().matches("\\d{4}.*")) {
            return false;
        }
        boolean ownGoal = "TRUE".equalsIgnoreCase(f[6].trim());
        boolean penalty = "TRUE".equalsIgnoreCase(f[7].trim());
        if (!ownGoal) {
            v[OFF_GOALS] = 1.0;
            if (penalty) {
                v[OFF_PENS] = 1.0;
            }
        } else {
            v[OFF_OGS] = 1.0;
        }
        return true;
    }

    private static String field(String[] f, int idx) {
        return idx < f.length ? f[idx].trim() : "";
    }
}
