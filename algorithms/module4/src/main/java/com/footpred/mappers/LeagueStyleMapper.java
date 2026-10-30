package com.footpred.mappers;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.VectorWritable;

/**
 * Job P6.6 (history p66): league style evolution.
 *
 * Input: results_leagues_full.tsv (built locally from the 165 per-season
 * football-data CSVs), 22 columns:
 *   league season date home away fthg ftag hthg htag
 *   hs as hst ast hf af hc ac hy ay hr ar referee
 *
 * Technical columns may be empty for early seasons; every metric carries its
 * own valid-count so a missing column skips only that metric (a row is never
 * dropped wholesale - e.g. Ligue 1 2005/06 has shots but no fouls).
 *
 * Emitted key = league \t season, value layout (17):
 *   [0] n          [1] goals        [2] goalsN
 *   [3] halfGoals  [4] halfGoalsN   [5] shots       [6] shotsN
 *   [7] sot        [8] sotN         [9] fouls       [10] foulsN
 *   [11] corners   [12] cornersN    [13] yellows    [14] yellowsN
 *   [15] reds      [16] redsN
 */
public class LeagueStyleMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        ROWS, BAD_LINE
    }

    public static final int N_COLS = 22;
    public static final int DIM = 17;
    public static final int OFF_N = 0;
    public static final int OFF_GOALS = 1;
    public static final int OFF_GOALS_N = 2;
    public static final int OFF_HALF = 3;
    public static final int OFF_HALF_N = 4;
    public static final int OFF_SHOTS = 5;
    public static final int OFF_SHOTS_N = 6;
    public static final int OFF_SOT = 7;
    public static final int OFF_SOT_N = 8;
    public static final int OFF_FOULS = 9;
    public static final int OFF_FOULS_N = 10;
    public static final int OFF_CORNERS = 11;
    public static final int OFF_CORNERS_N = 12;
    public static final int OFF_YELLOWS = 13;
    public static final int OFF_YELLOWS_N = 14;
    public static final int OFF_REDS = 15;
    public static final int OFF_REDS_N = 16;

    private final Text outKey = new Text();

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        double[] v = parseRow(value.toString());
        if (v == null) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        String[] f = value.toString().trim().split("\t", -1);
        outKey.set(f[0] + "\t" + f[1]);
        context.getCounter(Counters.ROWS).increment(1);
        context.write(outKey, new VectorWritable(v));
    }

    /**
     * Parses one TSV row into the 17-slot accumulation vector, or null for
     * header / malformed rows. Package-visible static for unit tests.
     */
    static double[] parseRow(String line) {
        if (line == null) {
            return null;
        }
        // strip only the trailing newline / carriage return - a full trim()
        // would swallow the trailing tabs that encode empty fields
        String s = line;
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("\r")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }
        String[] f = s.split("\t", -1);
        if (f.length < N_COLS) {
            return null;
        }
        for (int i = 0; i < N_COLS; i++) {
            f[i] = f[i].trim();
        }
        if (!f[2].matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null; // header or bad date
        }
        if (f[0].isEmpty() || f[1].isEmpty()) {
            return null;
        }
        double[] v = new double[DIM];
        Integer fthg = intOrNull(f[5]);
        Integer ftag = intOrNull(f[6]);
        if (fthg == null || ftag == null) {
            return null; // a row without final scores is unusable
        }
        v[OFF_N] = 1.0;
        v[OFF_GOALS] = fthg + ftag;
        v[OFF_GOALS_N] = 1.0;
        addPair(v, intOrNull(f[7]), intOrNull(f[8]), OFF_HALF, OFF_HALF_N);
        addPair(v, intOrNull(f[9]), intOrNull(f[10]), OFF_SHOTS, OFF_SHOTS_N);
        addPair(v, intOrNull(f[11]), intOrNull(f[12]), OFF_SOT, OFF_SOT_N);
        addPair(v, intOrNull(f[13]), intOrNull(f[14]), OFF_FOULS, OFF_FOULS_N);
        addPair(v, intOrNull(f[15]), intOrNull(f[16]), OFF_CORNERS, OFF_CORNERS_N);
        addPair(v, intOrNull(f[17]), intOrNull(f[18]), OFF_YELLOWS, OFF_YELLOWS_N);
        addPair(v, intOrNull(f[19]), intOrNull(f[20]), OFF_REDS, OFF_REDS_N);
        return v;
    }

    /** Adds the home+away sum of one metric if both sides are present. */
    private static void addPair(double[] v, Integer home, Integer away,
                                int sumOff, int nOff) {
        if (home == null || away == null) {
            return;
        }
        v[sumOff] += home + away;
        v[nOff] += 1.0;
    }

    static Integer intOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
