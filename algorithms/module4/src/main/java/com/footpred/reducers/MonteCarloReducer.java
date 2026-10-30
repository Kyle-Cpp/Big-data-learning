package com.footpred.reducers;

import java.io.IOException;
import java.util.Locale;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

import com.footpred.common.SimAggregate;
import com.footpred.common.SimContext;
import com.footpred.common.VectorWritable;

/**
 * Job P5.2 - Monte-Carlo season simulation, reduce side.
 *
 * Sums the per-team partial accumulators (already pre-aggregated by the
 * combiner), normalises to probabilities and emits one row per team with
 * the actual final table appended for side-by-side comparison.
 *
 * Output line (TSV, key is written as the first two fields):
 *
 *   league <TAB> team <TAB> avgPoints <TAB> sdPoints <TAB> avgRank <TAB>
 *   pChampion <TAB> pTop <TAB> pRelegated <TAB> realRank <TAB> realPoints <TAB>
 *   rankHist("1:0.641|2:0.247|...")
 */
public class MonteCarloReducer extends Reducer<Text, VectorWritable, Text, Text> {

    private SimContext ctx;

    @Override
    protected void setup(Context context) throws IOException {
        String blob = context.getConfiguration().get("sim.context");
        if (blob == null) {
            throw new IOException("sim.context missing in configuration");
        }
        ctx = SimContext.decode(blob);
    }

    @Override
    protected void reduce(Text key, Iterable<VectorWritable> values, Context context)
            throws IOException, InterruptedException {
        String[] kf = key.toString().split("\t", 2);
        if (kf.length != 2) {
            throw new IOException("malformed simulation key: " + key);
        }
        String leagueName = kf[0];
        String team = kf[1];
        SimContext.League league = ctx.leagueByName(leagueName);
        if (league == null) {
            throw new IOException("unknown league in key: " + leagueName);
        }
        int local = league.teamNames.indexOf(team);
        if (local < 0) {
            throw new IOException("team not on " + leagueName + " roster: " + team);
        }
        double[] acc = VectorSumCombiner.sum(values);
        SimAggregate.Stats stats = SimAggregate.stats(acc);

        StringBuilder hist = new StringBuilder();
        for (int r = 0; r < stats.rankHist.length; r++) {
            if (r > 0) {
                hist.append('|');
            }
            hist.append(r + 1).append(':')
                    .append(String.format(Locale.ROOT, "%.3f", stats.rankHist[r]));
        }
        String row = String.format(Locale.ROOT,
                "%.2f\t%.2f\t%.2f\t%.4f\t%.4f\t%.4f\t%d\t%d\t%s",
                stats.avgPoints, stats.sdPoints, stats.avgRank,
                stats.pChampion, stats.pTop, stats.pRelegated,
                league.realRank[local], league.realPoints[local], hist.toString());
        context.write(key, new Text(row));
    }
}
