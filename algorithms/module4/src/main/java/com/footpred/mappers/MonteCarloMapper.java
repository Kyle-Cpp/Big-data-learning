package com.footpred.mappers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.SeasonSimulator;
import com.footpred.common.SimAggregate;
import com.footpred.common.SimContext;
import com.footpred.common.VectorWritable;

/**
 * Job P5.2 - Monte-Carlo season simulation, map side.
 *
 * Input: a driver-generated seed-segment file, exactly one line per
 * mapper task:
 *
 *     mapIndex <TAB> nSims
 *
 * (delivered as one split per line via NLineInputFormat, so the sim count
 * shards across mappers while every mapper sees the full broadcast context).
 *
 * Each line triggers {@code nSims} independent season rollouts from its own
 * deterministic Random stream (base seed + mapIndex, so replays with the
 * same --seed reproduce every sampled season). Outcomes are pre-aggregated
 * locally per team into one SimAggregate vector; the combiner then shrinks
 * the shuffle to one partial vector per team per mapper, and the single
 * reducer emits the per-team distribution statistics.
 *
 * Emit (cleanup): key "league \t team", value SimAggregate accumulator.
 */
public class MonteCarloMapper extends Mapper<LongWritable, Text, Text, VectorWritable> {

    public enum Counters {
        SEGMENTS, SIMS_RUN, BAD_SEGMENT_LINE
    }

    private SimContext ctx;
    private long baseSeed;

    /** league name -> per-team (local idx) accumulator rows. */
    private final Map<String, double[][]> accByLeague = new HashMap<String, double[][]>();

    @Override
    protected void setup(Context context) throws IOException {
        String blob = context.getConfiguration().get("sim.context");
        if (blob == null) {
            throw new IOException("sim.context missing in configuration");
        }
        ctx = SimContext.decode(blob);
        baseSeed = context.getConfiguration().getLong("sim.seed", 42L);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString().trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        String[] f = line.split("\t");
        if (f.length != 2) {
            context.getCounter(Counters.BAD_SEGMENT_LINE).increment(1);
            return;
        }
        long mapIndex;
        int nSims;
        try {
            mapIndex = Long.parseLong(f[0]);
            nSims = Integer.parseInt(f[1]);
        } catch (NumberFormatException e) {
            context.getCounter(Counters.BAD_SEGMENT_LINE).increment(1);
            return;
        }
        if (nSims < 0) {
            context.getCounter(Counters.BAD_SEGMENT_LINE).increment(1);
            return;
        }
        context.getCounter(Counters.SEGMENTS).increment(1);
        Random rnd = new Random(baseSeed + 1_000_003L * mapIndex);
        for (int s = 0; s < nSims; s++) {
            recordOutcomes(SeasonSimulator.simulate(ctx, rnd));
        }
        context.getCounter(Counters.SIMS_RUN).increment(nSims);
    }

    private void recordOutcomes(Iterable<SeasonSimulator.LeagueOutcome> outcomes) {
        for (SeasonSimulator.LeagueOutcome outcome : outcomes) {
            SimContext.League league = ctx.leagues.get(outcome.ordinal);
            double[][] rows = accByLeague.get(league.name);
            if (rows == null) {
                rows = new double[league.nTeams][];
                for (int i = 0; i < league.nTeams; i++) {
                    rows[i] = SimAggregate.newAccumulator(league.nTeams);
                }
                accByLeague.put(league.name, rows);
            }
            for (int i = 0; i < league.nTeams; i++) {
                SimAggregate.record(rows[i], outcome.points[i], outcome.ranks[i],
                        league.nTeams, league.topPlaces, league.relegationPlaces);
            }
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        for (Map.Entry<String, double[][]> entry : accByLeague.entrySet()) {
            String leagueName = entry.getKey();
            double[][] rows = entry.getValue();
            for (int i = 0; i < rows.length; i++) {
                String team = teamName(leagueName, i);
                context.write(new Text(leagueName + "\t" + team),
                        new VectorWritable(rows[i]));
            }
        }
    }

    private String teamName(String leagueName, int localIdx) {
        SimContext.League league = ctx.leagueByName(leagueName);
        if (league == null) {
            throw new IllegalStateException("lost league during map: " + leagueName);
        }
        return league.teamNames.get(localIdx);
    }
}
