package com.footpred.future;

import com.footpred.common.DCModel;
import com.footpred.common.MatchParser;
import com.footpred.common.RawMatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** P5.0: validate, deduplicate, and split observed matches from score-free fixtures. */
public final class SeasonPrepareJob {
    private SeasonPrepareJob() { }
    public enum Counts { MALFORMED, OUTSIDE_WINDOW, UNKNOWN_TEAM, DUPLICATES, PLAYED, REMAINING }

    public static final class PrepareMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Set<String> teams = new HashSet<String>();
        private long start, end;
        private String league;
        @Override protected void setup(Context ctx) throws IOException {
            Configuration c = ctx.getConfiguration();
            DCModel model = ReplaySupport.model(c);
            teams.addAll(model.teamNames);
            start = c.getLong("replay.start", 0); end = c.getLong("replay.end", 0);
            league = c.get("replay.league");
        }
        @Override protected void map(LongWritable key, Text value, Context ctx)
                throws IOException, InterruptedException {
            RawMatch m = MatchParser.parse(value.toString());
            if (m == null || m.homeTeam.equals(m.awayTeam) || m.tournament.isEmpty()) {
                ctx.getCounter(Counts.MALFORMED).increment(1); return;
            }
            if (m.epochDay < start || m.epochDay > end
                    || (league != null && !league.equals(m.tournament))) {
                ctx.getCounter(Counts.OUTSIDE_WINDOW).increment(1); return;
            }
            if (!teams.contains(m.homeTeam) || !teams.contains(m.awayTeam)) {
                ctx.getCounter(Counts.UNKNOWN_TEAM).increment(1); return;
            }
            ctx.write(new Text(m.epochDay + "\t" + m.tournament + "\t" + m.homeTeam
                    + "\t" + m.awayTeam), new Text(ReplaySupport.match(m)));
        }
    }

    public static final class PrepareReducer extends Reducer<Text, Text, NullWritable, Text> {
        private MultipleOutputs<NullWritable, Text> outputs;
        @Override protected void setup(Context ctx) { outputs = new MultipleOutputs<NullWritable, Text>(ctx); }
        @Override protected void reduce(Text key, Iterable<Text> values, Context ctx)
                throws IOException, InterruptedException {
            String selected = null;
            for (Text value : values) {
                String row = value.toString();
                if (selected != null) {
                    if (!selected.equals(row)) throw new IOException("Conflicting duplicate match: " + key);
                    ctx.getCounter(Counts.DUPLICATES).increment(1);
                } else selected = row;
            }
            RawMatch m = ReplaySupport.match(selected);
            outputs.write("truth", NullWritable.get(), new Text(selected), "truth/part");
            if (m.epochDay < ctx.getConfiguration().getLong("replay.cutoff", 0)) {
                outputs.write("played", NullWritable.get(), new Text(selected), "played/part");
                ctx.getCounter(Counts.PLAYED).increment(1);
            } else {
                outputs.write("fixtures", NullWritable.get(), new Text(ReplaySupport.fixture(m)), "fixtures/part");
                ctx.getCounter(Counts.REMAINING).increment(1);
            }
        }
        @Override protected void cleanup(Context ctx) throws IOException, InterruptedException { outputs.close(); }
    }

    public static Job configure(Configuration c, Path input, Path output) throws IOException {
        Job j = ReplaySupport.job(c, "P5.0-season-prepare-and-cutoff-split", output);
        j.setMapperClass(PrepareMapper.class); j.setReducerClass(PrepareReducer.class);
        ReplaySupport.input(j, input);
        for (String name : new String[]{"played", "fixtures", "truth"}) ReplaySupport.named(j, name);
        return j;
    }
}
