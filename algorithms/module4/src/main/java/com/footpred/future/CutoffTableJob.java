package com.footpred.future;

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
import java.io.IOException;

/** P5.1: aggregate points, goals and appearances; fixtures supply zero-match roster entries. */
public final class CutoffTableJob {
    private CutoffTableJob() { }
    public static final class PlayedMapper extends Mapper<LongWritable, Text, Text, Text> {
        @Override protected void map(LongWritable key, Text value, Context ctx)
                throws IOException, InterruptedException {
            RawMatch m = ReplaySupport.match(value.toString());
            emit(ctx, m.tournament, m.homeTeam, m.homeScore, m.awayScore);
            emit(ctx, m.tournament, m.awayTeam, m.awayScore, m.homeScore);
        }
        private void emit(Context ctx, String league, String team, int gf, int ga)
                throws IOException, InterruptedException {
            ctx.write(new Text(league + "\t" + team), new Text((gf > ga ? 3 : gf == ga ? 1 : 0)
                    + "\t" + gf + "\t" + ga + "\t1"));
        }
    }
    public static final class RosterMapper extends Mapper<LongWritable, Text, Text, Text> {
        @Override protected void map(LongWritable key, Text value, Context ctx)
                throws IOException, InterruptedException {
            String[] f = value.toString().split("\t", -1);
            for (int i = 2; i <= 3; i++) ctx.write(new Text(f[1] + "\t" + f[i]), new Text("0\t0\t0\t0"));
        }
    }
    public static final class SumCombiner extends Reducer<Text, Text, Text, Text> {
        @Override protected void reduce(Text key, Iterable<Text> values, Context ctx)
                throws IOException, InterruptedException { ctx.write(key, new Text(sum(values))); }
    }
    public static final class TableReducer extends Reducer<Text, Text, NullWritable, Text> {
        @Override protected void reduce(Text key, Iterable<Text> values, Context ctx)
                throws IOException, InterruptedException { ctx.write(NullWritable.get(), new Text(key + "\t" + sum(values))); }
    }
    private static String sum(Iterable<Text> values) {
        long[] totals = new long[4];
        for (Text value : values) {
            String[] f = value.toString().split("\t");
            for (int i = 0; i < 4; i++) totals[i] += Long.parseLong(f[i]);
        }
        return totals[0] + "\t" + totals[1] + "\t" + totals[2] + "\t" + totals[3];
    }
    public static Job configure(Configuration c, Path played, Path fixtures, Path output) throws IOException {
        Job j = ReplaySupport.job(c, "P5.1-cutoff-team-table", output);
        MultipleInputs.addInputPath(j, played, TextInputFormat.class, PlayedMapper.class);
        MultipleInputs.addInputPath(j, fixtures, TextInputFormat.class, RosterMapper.class);
        j.setCombinerClass(SumCombiner.class); j.setReducerClass(TableReducer.class);
        return j;
    }
}
