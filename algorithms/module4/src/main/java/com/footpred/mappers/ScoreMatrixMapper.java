package com.footpred.mappers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import com.footpred.common.DCModel;
import com.footpred.common.ScoreMatrixCalculator;

/**
 * Job P1.5 - score matrix generation, map side.
 *
 * Input: fixture file, one match per line (TSV):
 *
 *     homeTeam <TAB> awayTeam <TAB> neutral(0|1)
 *
 * Lines starting with '#' are comments. Team names must match the team
 * registry (identical spelling as in results.csv / model.json).
 *
 * The current model is broadcast via the configuration; the mapper resolves
 * team indices and calculates the complete DC-corrected score matrix. This is
 * a map-only job: each input fixture produces one independent output row.
 */
public class ScoreMatrixMapper extends Mapper<LongWritable, Text, Text, Text> {

    public enum Counters {
        FIXTURES, UNKNOWN_TEAM, BAD_LINE
    }

    private DCModel model;
    private int maxGoals;
    private final Map<String, Integer> teamIndex = new HashMap<String, Integer>();

    @Override
    protected void setup(Context context) throws IOException {
        String blob = context.getConfiguration().get("dc.model");
        if (blob == null) {
            throw new IOException("dc.model (Base64 parameter vector) missing in configuration");
        }
        model = DCModel.fromBase64(blob);
        maxGoals = context.getConfiguration().getInt("fp.max.goals", 10);
        for (int i = 0; i < model.nTeams; i++) {
            teamIndex.put(model.teamNames.get(i), i);
        }
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString().trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        String[] f = line.split("\t");
        if (f.length < 3) {
            context.getCounter(Counters.BAD_LINE).increment(1);
            return;
        }
        String home = f[0].trim();
        String away = f[1].trim();
        boolean neutral = "1".equals(f[2].trim()) || "true".equalsIgnoreCase(f[2].trim());

        Integer homeIdx = teamIndex.get(home);
        Integer awayIdx = teamIndex.get(away);
        if (homeIdx == null || awayIdx == null) {
            context.getCounter(Counters.UNKNOWN_TEAM).increment(1);
            System.err.println("UNKNOWN fixture team: home=" + home + " away=" + away);
            return;
        }

        double lambda = model.lambdaHome(homeIdx, awayIdx, neutral);
        double mu = model.lambdaAway(homeIdx, awayIdx);
        context.getCounter(Counters.FIXTURES).increment(1);
        context.write(new Text(home + " vs " + away),
                new Text(ScoreMatrixCalculator.calculate(lambda, mu, model.rho, maxGoals)));
    }
}
