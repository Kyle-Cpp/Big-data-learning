package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.VectorWritable;
import com.footpred.mappers.LeagueStyleMapper;

/**
 * Job P6.6 reducer: one row per (league, season) with per-game averages.
 * Metrics with zero valid matches in that season emit an empty field instead
 * of a misleading 0 (e.g. Ligue 1 fouls before 2007/08).
 *
 * Output: league \t season \t nGames \t goalsPG \t halfGoalsPG \t shotsPG
 *         \t sotPG \t foulsPG \t cornersPG \t yellowsPG \t redsPG
 */
public class LeagueStyleReducer extends Reducer<Text, VectorWritable, NullWritable, Text> {

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        double[] t = VectorSumCombiner.sum(values);
        if (t == null || t.length < LeagueStyleMapper.DIM) {
            return;
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append(key.toString());
        sb.append('\t').append((long) t[LeagueStyleMapper.OFF_N]);
        perGame(sb, t, LeagueStyleMapper.OFF_GOALS, LeagueStyleMapper.OFF_GOALS_N);
        perGame(sb, t, LeagueStyleMapper.OFF_HALF, LeagueStyleMapper.OFF_HALF_N);
        perGame(sb, t, LeagueStyleMapper.OFF_SHOTS, LeagueStyleMapper.OFF_SHOTS_N);
        perGame(sb, t, LeagueStyleMapper.OFF_SOT, LeagueStyleMapper.OFF_SOT_N);
        perGame(sb, t, LeagueStyleMapper.OFF_FOULS, LeagueStyleMapper.OFF_FOULS_N);
        perGame(sb, t, LeagueStyleMapper.OFF_CORNERS, LeagueStyleMapper.OFF_CORNERS_N);
        perGame(sb, t, LeagueStyleMapper.OFF_YELLOWS, LeagueStyleMapper.OFF_YELLOWS_N);
        perGame(sb, t, LeagueStyleMapper.OFF_REDS, LeagueStyleMapper.OFF_REDS_N);
        context.write(NullWritable.get(), new Text(sb.toString()));
    }

    /** Appends "\t sum/n" with 3 decimals, or an empty field when n = 0. */
    private static void perGame(StringBuilder sb, double[] t, int sumOff, int nOff) {
        double n = t[nOff];
        sb.append('\t');
        if (n > 0.0) {
            sb.append(String.format(Locale.ROOT, "%.3f", t[sumOff] / n));
        }
    }
}
