package com.footpred.future;

import com.footpred.common.IoUtils;
import com.footpred.common.SimContext;
import com.footpred.common.VectorWritable;
import com.footpred.mappers.MonteCarloMapper;
import com.footpred.reducers.MonteCarloReducer;
import com.footpred.reducers.VectorSumCombiner;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Five submitted jobs. Driver only assembles small prepared tables and seed segments. */
public final class FutureSeasonPipeline {
    private FutureSeasonPipeline() { }

    public static int run(Configuration base, Map<String, String> opts, Path model,
                          Path root) throws Exception {
        Configuration conf = new Configuration(base);
        String input = opts.get("input");
        if (input == null) throw new IllegalArgumentException("simulate needs --input");
        long start = LocalDate.parse(opts.getOrDefault("season-start", "2025-08-01")).toEpochDay();
        long cutoff = LocalDate.parse(opts.getOrDefault("cutoff", "2026-01-01")).toEpochDay();
        long end = LocalDate.parse(opts.getOrDefault("season-end", "2026-06-30")).toEpochDay();
        if (start >= cutoff || cutoff > end) throw new IllegalArgumentException("need season-start < cutoff <= season-end");
        int total = Integer.parseInt(opts.getOrDefault("total-sims", "10000"));
        int maps = Integer.parseInt(opts.getOrDefault("maps", "2"));
        int top = Integer.parseInt(opts.getOrDefault("top-places", "4"));
        if (total <= 0 || maps <= 0 || top <= 0) throw new IllegalArgumentException("sims, maps and top-places must be positive");
        maps = Math.min(maps, total);
        conf.set("replay.model.path", model.toString());
        conf.setLong("replay.start", start); conf.setLong("replay.cutoff", cutoff); conf.setLong("replay.end", end);
        conf.setInt("replay.top", top);
        if (opts.containsKey("league")) conf.set("replay.league", opts.get("league"));
        if (opts.containsKey("relegation-places") && !"auto".equals(opts.get("relegation-places"))) {
            int rel = Integer.parseInt(opts.get("relegation-places"));
            if (rel <= 0) throw new IllegalArgumentException("relegation-places must be positive or auto");
            conf.setInt("replay.relegation", rel);
        }
        IoUtils.requireInput(new Path(input), conf, "season matches");
        IoUtils.requireInput(model, conf, "club DC model");
        Path finalOutput = opts.containsKey("out") ? new Path(opts.get("out")) : new Path(root, "simulation");
        FileSystem fs = root.getFileSystem(conf);
        // The outer driver may already have created only its live log in root.
        if (fs.exists(root)) for (FileStatus item : fs.listStatus(root)) {
            if (!item.getPath().getName().equals("simulate_log.txt")) {
                throw new IOException("Run directory already contains data; choose a new --run-id: " + root);
            }
        }
        if (finalOutput.getFileSystem(conf).exists(finalOutput)) throw new IOException("Output already exists: " + finalOutput);
        fs.mkdirs(root);
        Path prepared = new Path(root, "p5-0-season-data");
        Path table = new Path(root, "p5-1-cutoff-table");
        Path rates = new Path(root, "p5-3-fixture-intensities");
        Path simulated = new Path(root, "p5-2-simulation");
        Path evaluation = new Path(root, "p5-4-evaluation");
        Path played = new Path(prepared, "played");
        Path fixtures = new Path(prepared, "fixtures");
        try (FSDataOutputStream manifest = fs.create(new Path(root, "job_manifest.tsv"), false)) {
            manifest.writeBytes("stage\tjob_name\tjob_id\tstatus\n");
            Job prepare = SeasonPrepareJob.configure(conf, new Path(input), prepared);
            if (!execute(prepare, "1", manifest)) return 1;
            long nPlayed = prepare.getCounters().findCounter(SeasonPrepareJob.Counts.PLAYED).getValue();
            long nRemaining = prepare.getCounters().findCounter(SeasonPrepareJob.Counts.REMAINING).getValue();
            if (nPlayed + nRemaining == 0) throw new IOException("No matches survived window/league/model-roster filters");
            // Empty branches are valid, e.g. a team has not yet played at cutoff.
            emptyBranch(fs, played); emptyBranch(fs, fixtures);
            if (!execute(CutoffTableJob.configure(conf, played, fixtures, table), "2", manifest)) return 2;
            if (!execute(FixtureIntensityJob.configure(conf, fixtures, rates), "3", manifest)) return 3;
            SimContext context = context(conf, table, rates);
            Path contextFile = new Path(root, "replay_context.tsv");
            write(fs, contextFile, context.encode());
            Path seeds = new Path(root, "seed-segments.tsv");
            StringBuilder seedRows = new StringBuilder();
            for (int i = 0; i < maps; i++) seedRows.append(i).append('\t')
                    .append(total / maps + (i < total % maps ? 1 : 0)).append('\n');
            write(fs, seeds, seedRows.toString());
            Job simulation = simulation(conf, context, seeds, simulated,
                    Long.parseLong(opts.getOrDefault("seed", "42")));
            if (!execute(simulation, "4", manifest)) return 4;
            long actual = simulation.getCounters().findCounter(MonteCarloMapper.Counters.SIMS_RUN).getValue();
            if (actual != total) throw new IOException("Expected " + total + " simulations, got " + actual);
            if (!execute(ReplayEvaluationJob.configure(conf, simulated, new Path(prepared, "truth"), evaluation), "5", manifest)) return 5;
        }
        // Export legacy consumer schemas from evaluation, never overwrite a run.
        FileSystem outFs = finalOutput.getFileSystem(conf);
        outFs.mkdirs(finalOutput);
        IoUtils.mergeTextDir(new Path(evaluation, "simulation"), new Path(finalOutput, "part-r-00000"), conf);
        write(outFs, new Path(finalOutput, "_SUCCESS"), "");
        IoUtils.mergeTextDir(new Path(evaluation, "actual"), new Path(root, "real_final_table.tsv"), conf);
        System.err.println("[footpred] five-job pipeline complete: " + root);
        System.err.println("[footpred] predictions: " + finalOutput);
        System.err.println("[footpred] evaluation: " + evaluation);
        return 0;
    }

    private static boolean execute(Job job, String stage, FSDataOutputStream manifest) throws Exception {
        System.err.println("[footpred] stage " + stage + "/5: " + job.getJobName());
        boolean ok = job.waitForCompletion(true);
        manifest.write((stage + "\t" + job.getJobName() + "\t" + job.getJobID() + "\t"
                + (ok ? "SUCCEEDED" : "FAILED") + "\n").getBytes(StandardCharsets.UTF_8));
        manifest.hflush();
        return ok;
    }

    private static void emptyBranch(FileSystem fs, Path path) throws IOException {
        if (!fs.exists(path)) { fs.mkdirs(path); write(fs, new Path(path, "empty.tsv"), ""); }
    }
    private static void write(FileSystem fs, Path file, String text) throws IOException {
        try (FSDataOutputStream out = fs.create(file, false)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    static SimContext context(Configuration conf, Path table, Path rates) throws IOException {
        Map<String, List<String[]>> rows = new TreeMap<String, List<String[]>>();
        for (String line : IoUtils.readAllLinesInDir(table, conf)) {
            String[] f = line.split("\t");
            if (!rows.containsKey(f[0])) rows.put(f[0], new ArrayList<String[]>());
            rows.get(f[0]).add(f);
        }
        List<SimContext.League> leagues = new ArrayList<SimContext.League>();
        for (Map.Entry<String, List<String[]>> e : rows.entrySet()) {
            List<String[]> data = e.getValue();
            Collections.sort(data, new Comparator<String[]>() {
                public int compare(String[] a, String[] b) { return a[1].compareTo(b[1]); }
            });
            List<String> names = new ArrayList<String>();
            int n = data.size(); int[] points = new int[n], gf = new int[n], ga = new int[n], played = new int[n];
            for (int i = 0; i < n; i++) {
                String[] f = data.get(i); names.add(f[1]); points[i] = Integer.parseInt(f[2]);
                gf[i] = Integer.parseInt(f[3]); ga[i] = Integer.parseInt(f[4]); played[i] = Integer.parseInt(f[5]);
            }
            if (n < 2) throw new IOException("League requires at least 2 teams: " + e.getKey());
            leagues.add(new SimContext.League(e.getKey(), ReplaySupport.relegation(e.getKey(), conf),
                    conf.getInt("replay.top", 4), names, points, gf, ga, played, new int[n], new int[n]));
        }
        List<SimContext.Fixture> matches = new ArrayList<SimContext.Fixture>();
        SimContext lookup = new SimContext(leagues, matches);
        for (String line : IoUtils.readAllLinesInDir(rates, conf)) {
            String[] f = line.split("\t"); SimContext.League league = lookup.leagueByName(f[1]);
            matches.add(new SimContext.Fixture(f[1], league.teamNames.indexOf(f[2]), league.teamNames.indexOf(f[3]),
                    Double.parseDouble(f[5]), Double.parseDouble(f[6]), Long.parseLong(f[0])));
        }
        Collections.sort(matches, new Comparator<SimContext.Fixture>() {
            public int compare(SimContext.Fixture a, SimContext.Fixture b) {
                int d = Long.compare(a.epochDay, b.epochDay); if (d != 0) return d;
                d = a.league.compareTo(b.league); if (d != 0) return d;
                d = Integer.compare(a.homeIdx, b.homeIdx); return d != 0 ? d : Integer.compare(a.awayIdx, b.awayIdx);
            }
        });
        return new SimContext(leagues, matches);
    }

    private static Job simulation(Configuration conf, SimContext ctx, Path seeds, Path output, long seed) throws IOException {
        Job j = Job.getInstance(conf, "P5.2-season-simulation");
        j.setJarByClass(FutureSeasonPipeline.class);
        j.setMapperClass(MonteCarloMapper.class); j.setCombinerClass(VectorSumCombiner.class);
        j.setReducerClass(MonteCarloReducer.class);
        j.setMapOutputKeyClass(Text.class); j.setMapOutputValueClass(VectorWritable.class);
        j.setOutputKeyClass(Text.class); j.setOutputValueClass(Text.class); j.setNumReduceTasks(1);
        j.setInputFormatClass(NLineInputFormat.class); NLineInputFormat.setNumLinesPerSplit(j, 1);
        j.getConfiguration().set("sim.context", ctx.encode()); j.getConfiguration().setLong("sim.seed", seed);
        NLineInputFormat.addInputPath(j, seeds); FileOutputFormat.setOutputPath(j, output);
        return j;
    }
}
