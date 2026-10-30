package com.footpred.future;

import com.footpred.common.DCModel;
import com.footpred.common.IoUtils;
import com.footpred.common.RawMatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import java.io.IOException;

/** Text contracts shared by the four new Module 4 jobs. */
public final class ReplaySupport {
    private ReplaySupport() { }

    public static DCModel model(Configuration conf) throws IOException {
        return DCModel.fromJson(String.join("\n", IoUtils.readAllLines(
                new Path(conf.get("replay.model.path")), conf)));
    }

    public static String match(RawMatch m) {
        return m.epochDay + "\t" + m.tournament + "\t" + m.homeTeam + "\t"
                + m.awayTeam + "\t" + m.homeScore + "\t" + m.awayScore + "\t" + m.neutral;
    }

    public static RawMatch match(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 7) throw new IllegalArgumentException("Invalid replay match: " + line);
        return new RawMatch(Long.parseLong(f[0]), f[2], f[3], Integer.parseInt(f[4]),
                Integer.parseInt(f[5]), f[1], Boolean.parseBoolean(f[6]));
    }

    public static String fixture(RawMatch m) {
        // Deliberately contains no home/away score, even in historical replay.
        return m.epochDay + "\t" + m.tournament + "\t" + m.homeTeam + "\t"
                + m.awayTeam + "\t" + m.neutral;
    }

    public static Job job(Configuration conf, String name, Path output) throws IOException {
        Job job = Job.getInstance(conf, name);
        job.setJarByClass(ReplaySupport.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(1);
        FileOutputFormat.setOutputPath(job, output);
        return job;
    }

    public static void named(Job job, String name) {
        MultipleOutputs.addNamedOutput(job, name, TextOutputFormat.class,
                NullWritable.class, Text.class);
    }

    public static void input(Job job, Path path) throws IOException {
        FileInputFormat.addInputPath(job, path);
    }

    public static int relegation(String league, Configuration conf) {
        int override = conf.getInt("replay.relegation", -1);
        if (override > 0) return override;
        return "Bundesliga".equals(league) || "Ligue 1".equals(league) ? 2 : 3;
    }
}
