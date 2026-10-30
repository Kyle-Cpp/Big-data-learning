package com.footpred.future;

import com.footpred.common.DCModel;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** P5.3: distributed inference of DC intensities, not a new training algorithm. */
public final class FixtureIntensityJob {
    private FixtureIntensityJob() { }
    public static final class IntensityMapper extends Mapper<LongWritable, Text, NullWritable, Text> {
        private DCModel model;
        private final Map<String, Integer> indices = new HashMap<String, Integer>();
        @Override protected void setup(Context ctx) throws IOException {
            model = ReplaySupport.model(ctx.getConfiguration());
            for (int i = 0; i < model.nTeams; i++) indices.put(model.teamNames.get(i), i);
        }
        @Override protected void map(LongWritable key, Text value, Context ctx)
                throws IOException, InterruptedException {
            String[] f = value.toString().split("\t", -1);
            Integer home = indices.get(f[2]), away = indices.get(f[3]);
            if (home == null || away == null) throw new IOException("Fixture team missing from model");
            double lh = model.lambdaHome(home, away, Boolean.parseBoolean(f[4]));
            double la = model.lambdaAway(home, away);
            ctx.write(NullWritable.get(), new Text(value + "\t" + lh + "\t" + la));
            ctx.getCounter("Replay", "SCORED_FIXTURES").increment(1);
        }
    }
    public static Job configure(Configuration c, Path fixtures, Path output) throws IOException {
        Job j = ReplaySupport.job(c, "P5.3-remaining-fixture-dc-intensities", output);
        j.setMapperClass(IntensityMapper.class); j.setNumReduceTasks(0);
        ReplaySupport.input(j, fixtures);
        return j;
    }
}
