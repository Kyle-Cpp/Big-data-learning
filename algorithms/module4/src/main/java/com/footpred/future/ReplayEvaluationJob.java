package com.footpred.future;

import com.footpred.common.LeagueTable;
import com.footpred.common.RawMatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** P5.4: join predictions with held-out truth after simulation; preserve final TSV schema. */
public final class ReplayEvaluationJob {
    private ReplayEvaluationJob() { }
    public static final class TruthMapper extends Mapper<LongWritable, Text, Text, Text> {
        @Override protected void map(LongWritable k, Text v, Context ctx) throws IOException, InterruptedException {
            ctx.write(new Text(ReplaySupport.match(v.toString()).tournament), new Text("T\t" + v));
        }
    }
    public static final class PredictionMapper extends Mapper<LongWritable, Text, Text, Text> {
        @Override protected void map(LongWritable k, Text v, Context ctx) throws IOException, InterruptedException {
            String[] f = v.toString().split("\t", -1);
            if (f.length != 11) throw new IOException("Invalid simulation row: " + v);
            ctx.write(new Text(f[0]), new Text("P\t" + v));
        }
    }
    public static final class EvaluationReducer extends Reducer<Text, Text, NullWritable, Text> {
        private MultipleOutputs<NullWritable, Text> outputs;
        @Override protected void setup(Context ctx) { outputs = new MultipleOutputs<NullWritable, Text>(ctx); }
        @Override protected void reduce(Text key, Iterable<Text> values, Context ctx)
                throws IOException, InterruptedException {
            List<RawMatch> matches = new ArrayList<RawMatch>();
            Map<String, String[]> predictions = new TreeMap<String, String[]>();
            TreeSet<String> roster = new TreeSet<String>();
            for (Text v : values) {
                String row = v.toString();
                if (row.startsWith("T\t")) {
                    RawMatch m = ReplaySupport.match(row.substring(2)); matches.add(m);
                    roster.add(m.homeTeam); roster.add(m.awayTeam);
                } else {
                    String[] f = row.substring(2).split("\t", -1);
                    if (predictions.put(f[1], f) != null) throw new IOException("Duplicate prediction for " + f[1]);
                }
            }
            if (!roster.equals(predictions.keySet())) throw new IOException("Prediction/truth roster mismatch: " + key);
            LeagueTable actual = new LeagueTable(key.toString(), new ArrayList<String>(roster));
            for (RawMatch m : matches) actual.applyResult(actual.teamNames.indexOf(m.homeTeam),
                    actual.teamNames.indexOf(m.awayTeam), m.homeScore, m.awayScore);
            int[] ranks = actual.ranks();
            double pointError = 0, rankError = 0, titleBrier = 0, topBrier = 0, relBrier = 0;
            int top = ctx.getConfiguration().getInt("replay.top", 4);
            int relegation = ReplaySupport.relegation(key.toString(), ctx.getConfiguration());
            for (int i = 0; i < actual.nTeams; i++) {
                String team = actual.teamNames.get(i); String[] p = predictions.get(team);
                pointError += Math.abs(Double.parseDouble(p[2]) - actual.points[i]);
                rankError += Math.abs(Double.parseDouble(p[4]) - ranks[i]);
                titleBrier += square(Double.parseDouble(p[5]) - (ranks[i] == 1 ? 1 : 0));
                topBrier += square(Double.parseDouble(p[6]) - (ranks[i] <= top ? 1 : 0));
                relBrier += square(Double.parseDouble(p[7]) - (ranks[i] > actual.nTeams - relegation ? 1 : 0));
                p[8] = Integer.toString(ranks[i]); p[9] = Integer.toString(actual.points[i]);
                outputs.write("predictions", NullWritable.get(), new Text(String.join("\t", p)), "simulation/part");
                outputs.write("actual", NullWritable.get(), new Text(key + "\t" + team + "\t" + ranks[i]
                        + "\t" + actual.points[i] + "\t" + actual.played[i] + "\t" + actual.goalsFor[i]
                        + "\t" + actual.goalsAgainst[i] + "\t" + (actual.goalsFor[i] - actual.goalsAgainst[i])), "actual/part");
            }
            metric(ctx, key, "teams", actual.nTeams);
            metric(ctx, key, "points_mae", pointError / actual.nTeams);
            metric(ctx, key, "rank_mae", rankError / actual.nTeams);
            metric(ctx, key, "title_brier", titleBrier / actual.nTeams);
            metric(ctx, key, "top_brier", topBrier / actual.nTeams);
            metric(ctx, key, "relegation_brier", relBrier / actual.nTeams);
        }
        private void metric(Context c, Text league, String name, double value) throws IOException, InterruptedException {
            c.write(NullWritable.get(), new Text(league + "\t" + name + "\t" + String.format(Locale.ROOT, "%.8f", value)));
        }
        @Override protected void cleanup(Context ctx) throws IOException, InterruptedException { outputs.close(); }
    }
    private static double square(double x) { return x * x; }
    public static Job configure(Configuration c, Path predictions, Path truth, Path output) throws IOException {
        Job j = ReplaySupport.job(c, "P5.4-season-replay-evaluation", output);
        MultipleInputs.addInputPath(j, predictions, TextInputFormat.class, PredictionMapper.class);
        MultipleInputs.addInputPath(j, truth, TextInputFormat.class, TruthMapper.class);
        j.setReducerClass(EvaluationReducer.class);
        ReplaySupport.named(j, "predictions"); ReplaySupport.named(j, "actual");
        return j;
    }
}
