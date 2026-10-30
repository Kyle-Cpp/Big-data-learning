package com.footpred.driver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.footpred.common.DCModel;
import com.footpred.common.FilterTeeStream;
import com.footpred.common.IoUtils;
import com.footpred.common.LeagueTable;
import com.footpred.common.MatchParser;
import com.footpred.common.RawMatch;
import com.footpred.common.Shrinkage;
import com.footpred.common.SimAggregate;
import com.footpred.common.SimContext;
import com.footpred.common.TeamRegistry;
import com.footpred.common.TimeDecay;
import com.footpred.common.VectorWritable;
import com.footpred.common.WholeFileInputFormat;
import com.footpred.mappers.BaselineEvalMapper;
import com.footpred.mappers.BaselineStatsMapper;
import com.footpred.mappers.CareerCsvMapper;
import com.footpred.mappers.DCMapper;
import com.footpred.mappers.EvalMapper;
import com.footpred.mappers.LeagueStyleMapper;
import com.footpred.mappers.LineupFlattenMapper;
import com.footpred.mappers.MessiEventsMapper;
import com.footpred.mappers.MonteCarloMapper;
import com.footpred.mappers.NationalityMapper;
import com.footpred.mappers.ScoreDiffMapper;
import com.footpred.mappers.ScoreMatrixMapper;
import com.footpred.mappers.TeamRegistryMapper;
import com.footpred.mappers.TeamWeightMapper;
import com.footpred.mappers.TrainingDataMapper;
import com.footpred.optim.LBFGS;
import com.footpred.future.FutureSeasonPipeline;
import com.footpred.reducers.BaselineEvalReducer;
import com.footpred.reducers.BaselineStatsReducer;
import com.footpred.reducers.CareerReducer;
import com.footpred.reducers.DCGradientReducer;
import com.footpred.reducers.EvalReducer;
import com.footpred.reducers.LeagueStyleReducer;
import com.footpred.reducers.MessiEventsReducer;
import com.footpred.reducers.MonteCarloReducer;
import com.footpred.reducers.NationalityReducer;
import com.footpred.reducers.ScoreDiffReducer;
import com.footpred.reducers.TeamRegistryReducer;
import com.footpred.reducers.TeamWeightReducer;
import com.footpred.reducers.VectorSumCombiner;

/**
 * FootPred algorithm pipeline entry point (Module 1: Dixon-Coles).
 *
 * Sub-commands (run with `hadoop jar footpred-algo.jar <command> --k v ...`):
 *
 *   prepare    Job P0a + P0b: registry + indexed walk-forward training data
 *   train      Job P1 x T: iterative DC training (parameter broadcast +
 *              gradient reduction, L-BFGS driver-side update), saves model.json
 *   evaluate   validation-set RPS / logloss / accuracy on split=valid
 *   predict    Job P1.5: DC-corrected score matrices for a fixture file
 *   simulate   Job P5.2: Monte-Carlo season simulation (replay mode) with
 *              title / European-spot / relegation odds vs the actual table
 *   history    Module 5 analyses (P6.5 career curves, P6.6 league style
 *              evolution, P6.7 big-scoreline distribution, P6.8 nationality
 *              diversity), each a standard aggregate MapReduce job
 *
 * All heavy computation runs as MapReduce; the driver only orchestrates,
 * aggregates one line per iteration and updates parameters in memory.
 */
public class FootPredDriver extends Configured implements Tool {

    // ------------------------------------------------------------------ //
    //  Entry point                                                       //
    // ------------------------------------------------------------------ //

    public static void main(String[] args) throws Exception {
        int exit = ToolRunner.run(new Configuration(), new FootPredDriver(), args);
        System.exit(exit);
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return 1;
        }
        String command = args[0];
        Map<String, String> opts = parseOptions(args, 1);
        if ("simulate".equals(command) && !opts.containsKey("run-id")) {
            opts.put("run-id", timestampId("sim"));
        }
        Configuration conf = getConf();
        long started = System.currentTimeMillis();

        // ---- HDFS log tee: mirror the [footpred] trajectory lines into a
        // file so runs can be audited / cited without console scraping.
        // `train` defaults to <workdir>/train_log.txt (live, survives aborts);
        // every command accepts an explicit --log-file <hdfs path>.
        PrintStream originalErr = System.err;
        Path runDir = resolveRunDir(command, opts);
        if ("simulate".equals(command) && runDir != null
                && runDir.getFileSystem(conf).exists(new Path(runDir, "job_manifest.tsv"))) {
            throw new IOException("Run already exists; choose a new --run-id: " + runDir);
        }
        Path logPath = resolveLogPath(command, opts, runDir);
        FilterTeeStream logTee = null;
        if (logPath != null) {
            try {
                FileSystem fs = logPath.getFileSystem(conf);
                FSDataOutputStream logOut = fs.create(logPath, true);
                logTee = new FilterTeeStream(originalErr, logOut);
                System.setErr(new PrintStream(logTee, true, "UTF-8"));
                System.err.println("[footpred] log file: " + logPath);
            } catch (Exception e) {
                originalErr.println("[footpred] WARNING: cannot open log file " + logPath
                        + " (" + e.getMessage() + "), continuing console-only");
            }
        }

        int status = 1;
        try {
            if ("prepare".equals(command)) {
                status = prepare(conf, opts);
            } else if ("train".equals(command)) {
                status = train(conf, opts);
            } else if ("evaluate".equals(command)) {
                status = evaluate(conf, opts);
            } else if ("baseline".equals(command)) {
                status = baseline(conf, opts);
            } else if ("predict".equals(command)) {
                status = predict(conf, opts);
            } else if ("simulate".equals(command)) {
                status = simulate(conf, opts);
            } else if ("history".equals(command)) {
                status = history(conf, opts);
            } else {
                System.err.println("Unknown command: " + command);
                printUsage();
                status = 1;
            }
        } finally {
            System.err.printf(Locale.ROOT,
                    "[footpred] %s finished in %.1f s (status=%d)%n",
                    command, (System.currentTimeMillis() - started) / 1000.0, status);
            System.err.flush();
            if (logTee != null) {
                try {
                    logTee.close();
                } catch (IOException ignored) {
                    // nothing actionable while shutting the log down
                }
            }
            System.setErr(originalErr);
        }
        return status;
    }

    /**
     * Log destination: explicit --log-file, or the train default
     * <workdir>/runs/<run-id>/train_log.txt.
     */
    private Path resolveLogPath(String command, Map<String, String> opts, Path runDir) {
        String explicit = opts.get("log-file");
        if (explicit != null) {
            return new Path(explicit);
        }
        if ("train".equals(command) && runDir != null) {
            return new Path(runDir, "train_log.txt");
        }
        if ("simulate".equals(command) && runDir != null) {
            return new Path(runDir, "simulate_log.txt");
        }
        return null;
    }

    /**
     * Run directory for timestamped archiving:
     *   <workdir>/runs/<run-id>/
     * with run-id = --run-id or <train|eval>-<yyyyMMdd-HHmmss>.
     * The command name lives in opts? No - resolved from the command argument.
     */
    private Path resolveRunDir(String command, Map<String, String> opts) {
        String workdir = opts.get("workdir");
        if (workdir == null) {
            return null;
        }
        String runId;
        if ("train".equals(command)) {
            runId = opts.getOrDefault("run-id", timestampId("train"));
        } else if ("simulate".equals(command)) {
            runId = opts.getOrDefault("run-id", timestampId("sim"));
        } else if ("evaluate".equals(command) || "baseline".equals(command)) {
            runId = opts.getOrDefault("run-id", timestampId("eval"));
        } else if ("history".equals(command)) {
            runId = opts.getOrDefault("run-id", timestampId("hist"));
        } else {
            return null;
        }
        return new Path(new Path(workdir), "runs" + Path.SEPARATOR + runId);
    }

    private static String timestampId(String prefix) {
        return prefix + "-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /**
     * Model resolution for evaluate/baseline: explicit --model wins, then the
     * latest snapshot at {@code <workdir>/model.json}, then the newest archived
     * {@code <workdir>/runs/<id>/model.json}.
     */
    private Path resolveEvalModelPath(Map<String, String> opts, Path workdir, Configuration conf)
            throws IOException {
        String explicit = opts.get("model");
        if (explicit != null) {
            return new Path(explicit);
        }
        if (workdir == null) {
            throw new IllegalArgumentException("evaluate needs --model or --workdir");
        }
        Path latest = new Path(workdir, "model.json");
        FileSystem fs = workdir.getFileSystem(conf);
        if (fs.exists(latest)) {
            return latest;
        }
        Path runs = new Path(workdir, "runs");
        if (fs.exists(runs)) {
            Path best = null;
            long bestTime = -1;
            for (FileStatus status : fs.listStatus(runs)) {
                Path candidate = new Path(status.getPath(), "model.json");
                if (fs.exists(candidate) && status.getModificationTime() > bestTime) {
                    best = candidate;
                    bestTime = status.getModificationTime();
                }
            }
            if (best != null) {
                return best;
            }
        }
        throw new IllegalArgumentException("no model found: expected " + latest
                + " or <workdir>/runs/*/model.json (run train first, or pass --model)");
    }

    private static void printUsage() {
        System.err.println("Usage: hadoop jar footpred-algo.jar <command> [options]");
        System.err.println();
        System.err.println("  prepare    --input <results.csv hdfs path> --workdir <dir>");
        System.err.println("             [--former-names <csv>] [--start-date 2016-01-01]");
        System.err.println("             [--valid-date 2025-07-01]");
        System.err.println("  train      --workdir <dir> [--model-out <model.json>]");
        System.err.println("             [--half-life-days 730] [--friendly-weight 0.4]");
        System.err.println("             [--sigma2 <shrinkage prior variance, 0=off>]");
        System.err.println("             [--max-iter 60] [--lbfgs-history 8] [--ref-date <date>]");
        System.err.println("  evaluate   --workdir <dir> [--model <model.json>]");
        System.err.println("             [--eval-max-goals 12]");
        System.err.println("  baseline   --workdir <dir> [--model <model.json>]");
        System.err.println("             (B0/B1/B2 ladder + DC comparison, stratified)");
        System.err.println("  predict    --model <model.json> --fixtures <tsv>");
        System.err.println("             [--out <dir>] [--max-goals 10]");
        System.err.println("  simulate   --input <league results.csv> (--workdir <dir> | --model <model.json>)");
        System.err.println("             [--league \"Premier League\"] [--season-start 2025-08-01]");
        System.err.println("             [--cutoff 2026-01-01] [--season-end 2026-06-30]");
        System.err.println("             [--total-sims 10000] [--maps 2] [--seed 42]");
        System.err.println("             [--top-places 4] [--relegation-places auto|n] [--out <dir>]");
        System.err.println("             (Five jobs: P5.0 prepare, P5.1 table, P5.3 rates,");
        System.err.println("              P5.2 Monte-Carlo simulation, P5.4 replay evaluation)");
        System.err.println("             [--pipeline legacy] (explicit single-job regression mode)");
        System.err.println("             (Monte-Carlo season replay: title / European-");
        System.err.println("              spot / relegation odds vs the actual final table)");
        System.err.println("  history    --analysis p65|p66|p67|p68 --input <path[,path...]>");
        System.err.println("             [--events <staged events dir>] (p65 Messi club step)");
        System.err.println("             (--out <dir> | --workdir <dir>) [--run-id <id>]");
        System.err.println("             [--player-id 5503]");
        System.err.println("             (Module 5 historical analyses: p67 big-scoreline");
        System.err.println("              distribution, p66 league style evolution, p68 player");
        System.err.println("              nationality diversity, p65 player career curves)");
        System.err.println();
        System.err.println("  Common options:");
        System.err.println("  --run-id <id>   archive this run under <workdir>/runs/<id>/");
        System.err.println("                  (default: train-<ts> / eval-<ts>); train also keeps");
        System.err.println("                  a latest snapshot at <workdir>/model.json");
        System.err.println("  --log-file <hdfs path> mirrors the [footpred] trajectory lines into");
        System.err.println("                  that file (train defaults to its run dir log).");
    }

    // ------------------------------------------------------------------ //
    //  prepare: Job P0a + Job P0b                                        //
    // ------------------------------------------------------------------ //

    private int prepare(Configuration conf, Map<String, String> opts) throws Exception {
        Path input = new Path(require(opts, "input", "prepare needs --input (results.csv)"));
        Path workdir = new Path(require(opts, "workdir", "prepare needs --workdir"));
        String startDate = opts.getOrDefault("start-date", "2016-01-01");
        String validDate = opts.getOrDefault("valid-date", "2025-07-01");
        String formerNames = opts.get("former-names"); // optional

        IoUtils.requireInput(input, conf, "prepare input (results.csv)");
        FileSystem fs = workdir.getFileSystem(conf);
        fs.mkdirs(workdir);

        // ---- Job P0a: team registry (deterministic lexicographic indexing) ----
        Path registryTmp = new Path(workdir, "_tmp_registry");
        Path registryFile = new Path(workdir, "registry.tsv");
        IoUtils.deleteQuietly(registryTmp, conf);

        Job p0a = Job.getInstance(conf, "P0a-team-registry");
        p0a.setJarByClass(FootPredDriver.class);
        p0a.setMapperClass(TeamRegistryMapper.class);
        p0a.setCombinerClass(LongSumReducer.class);
        p0a.setReducerClass(TeamRegistryReducer.class);
        p0a.setMapOutputKeyClass(Text.class);
        p0a.setMapOutputValueClass(LongWritable.class);
        p0a.setOutputKeyClass(Text.class);
        p0a.setOutputValueClass(Text.class);
        p0a.setNumReduceTasks(1); // mandatory: stable index assignment
        p0a.getConfiguration().set("fp.start.date", startDate);
        FileInputFormat.addInputPath(p0a, input);
        FileOutputFormat.setOutputPath(p0a, registryTmp);
        if (!p0a.waitForCompletion(true)) {
            return fail("P0a team registry job failed");
        }
        // merge the single reducer output into one registry.tsv file
        if (fs.exists(registryFile)) {
            fs.delete(registryFile, false);
        }
        IoUtils.mergeTextDir(registryTmp, registryFile, conf);

        long nTeams = p0a.getCounters().findCounter(TeamRegistryReducer.Counters.N_TEAMS).getValue();
        System.err.printf(Locale.ROOT, "[footpred] P0a done: %d teams registered%n", nTeams);

        // ---- Job P0b: indexed training data with walk-forward split ----
        Path trainingData = new Path(workdir, "training_data");
        IoUtils.deleteQuietly(trainingData, conf);

        Job p0b = Job.getInstance(conf, "P0b-training-data");
        p0b.setJarByClass(FootPredDriver.class);
        p0b.setMapperClass(TrainingDataMapper.class);
        p0b.setMapOutputKeyClass(NullWritable.class);
        p0b.setMapOutputValueClass(Text.class);
        p0b.setOutputKeyClass(NullWritable.class);
        p0b.setOutputValueClass(Text.class);
        p0b.setNumReduceTasks(0); // pure transformation, map-only
        Configuration p0bConf = p0b.getConfiguration();
        p0bConf.set("fp.start.date", startDate);
        p0bConf.set("fp.valid.date", validDate);
        p0bConf.set("fp.registry.path", registryFile.toString());
        if (formerNames != null) {
            p0bConf.set("fp.former.names.path", formerNames);
        }
        FileInputFormat.addInputPath(p0b, input);
        FileOutputFormat.setOutputPath(p0b, trainingData);
        if (!p0b.waitForCompletion(true)) {
            return fail("P0b training data job failed");
        }

        long trainRows = p0b.getCounters()
                .findCounter(TrainingDataMapper.Counters.TRAIN_ROWS).getValue();
        long validRows = p0b.getCounters()
                .findCounter(TrainingDataMapper.Counters.VALID_ROWS).getValue();
        long unknown = p0b.getCounters()
                .findCounter(TrainingDataMapper.Counters.UNKNOWN_TEAM).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P0b done: train=%d valid=%d (window %s .. valid-from %s),"
                        + " unknown-team rows=%d%n",
                trainRows, validRows, startDate, validDate, unknown);
        if (trainRows == 0) {
            return fail("No training rows produced - check --start-date / input data");
        }
        return 0;
    }

    // ------------------------------------------------------------------ //
    //  train: iterative Job P1 with driver-side L-BFGS                   //
    // ------------------------------------------------------------------ //

    private int train(Configuration conf, Map<String, String> opts) throws Exception {
        Path workdir = new Path(require(opts, "workdir", "train needs --workdir"));
        Path trainingData = new Path(workdir, "training_data");
        Path registryFile = new Path(workdir, "registry.tsv");
        IoUtils.requireInput(trainingData, conf, "training data (run prepare first)");
        IoUtils.requireInput(registryFile, conf, "team registry (run prepare first)");

        String modelOutOpt = opts.get("model-out");
        Path runDir = resolveRunDir("train", opts);
        Path modelOut = modelOutOpt != null
                ? new Path(modelOutOpt)   // explicit destination: used as-is
                : new Path(runDir != null ? runDir : workdir, "model.json");
        double halfLifeDays = parseDouble(opts, "half-life-days", 730.0);
        double friendlyWeight = parseDouble(opts, "friendly-weight", 0.4);
        double sigma2 = parseDouble(opts, "sigma2", 0.0); // shrinkage prior; 0 = off
        int maxIter = (int) parseDouble(opts, "max-iter", 60);
        int lbfgsHistory = (int) parseDouble(opts, "lbfgs-history", 8);
        String validDate = opts.getOrDefault("valid-date", "2025-07-01");
        String refDate = opts.getOrDefault("ref-date", validDate);

        TeamRegistry registry = TeamRegistry.load(registryFile, conf);
        int nTeams = registry.size();
        long refEpochDay = LocalDate.parse(refDate).toEpochDay();
        double xi = TimeDecay.xiFromHalfLifeDays(halfLifeDays);

        DCModel model = new DCModel(nTeams, refEpochDay);
        model.teamNames.addAll(registry.namesByIndex());
        model.initialTheta();
        model.halfLifeDays = halfLifeDays;
        model.setFlat(model.toFlat()); // ensure centred start
        double[] theta = model.toFlat();

        LBFGS lbfgs = new LBFGS(lbfgsHistory);
        double step = 3e-4;   // first-step gradient-descent length
        boolean converged = false;
        GradResult last = null;

        System.err.printf(Locale.ROOT,
                "[footpred] train start: nTeams=%d dim=%d matches decay xi=%.6f"
                        + " (half-life %.0f d), friendly weight=%.2f, sigma2=%.3f%s,"
                        + " ref=%s, maxIter=%d%n",
                nTeams, theta.length, xi, halfLifeDays, friendlyWeight, sigma2,
                sigma2 > 0 ? " (shrinkage ON)" : " (shrinkage OFF)",
                refDate, maxIter);

        // evaluate the initial point (iteration 0)
        long t0 = System.currentTimeMillis();
        GradResult r0 = runGradientJob(conf, trainingData, workdir,
                model.toBase64(), xi, friendlyWeight, 0);
        last = r0;
        double[] g = negate(r0.grad);
        double f = -r0.ll + applyShrinkage(model, g, sigma2);
        System.err.printf(Locale.ROOT,
                "[footpred] iter=0 NLL=%.4f LL=%.4f |g|inf=%.4g wSum=%.1f n=%d"
                        + " badTau=%d took=%.1fs%n",
                f, r0.ll, maxAbs(g), r0.wSum, r0.count, r0.badTau,
                (System.currentTimeMillis() - t0) / 1000.0);

        double[] d = negate(g); // initial direction: steepest descent
        int attempted = 0;

        // one MapReduce job per attempted point; a rejected trial shrinks the
        // step along the SAME direction (backtracking), an accepted trial
        // rebuilds the direction from the fresh gradient - the objective is
        // monotonically non-increasing over accepted points
        for (attempted = 1; attempted <= maxIter; attempted++) {
            long ta = System.currentTimeMillis();

            double[] candidate = new double[theta.length];
            for (int i = 0; i < theta.length; i++) {
                candidate[i] = theta[i] + step * d[i];
            }
            model.setFlat(candidate);     // projection: re-centring + rho clip
            double[] candidateP = model.toFlat();

            GradResult rc = runGradientJob(conf, trainingData, workdir,
                    model.toBase64(), xi, friendlyWeight, attempted);
            last = rc;
            double[] gCand = negate(rc.grad);
            double fCand = -rc.ll + applyShrinkage(model, gCand, sigma2);

            boolean accept = fCand <= f + 1e-12 * Math.max(1.0, Math.abs(f));
            System.err.printf(Locale.ROOT,
                    "[footpred] try=%d %s NLL=%.4f (%+.3g) |g|inf=%.4g step=%.3g"
                            + " hist=%d badTau=%d took=%.1fs%n",
                    attempted, accept ? "accept" : "reject", fCand, fCand - f,
                    maxAbs(gCand), step, lbfgs.historyDepth(), rc.badTau,
                    (System.currentTimeMillis() - ta) / 1000.0);

            if (accept) {
                lbfgs.update(candidateP, theta, gCand, g);
                if (Math.abs(f - fCand) <= Math.max(1e-6, 1e-10 * Math.abs(f))) {
                    theta = candidateP;
                    g = gCand;
                    f = fCand;
                    converged = true;
                    System.err.printf(Locale.ROOT,
                            "[footpred] converged: |dF|=%.3g <= tol%n", Math.abs(f - fCand));
                    break;
                }
                theta = candidateP;
                g = gCand;
                f = fCand;
                d = lbfgs.hasHistory() ? lbfgs.computeDirection(g) : negate(g);
                step = 1.0; // L-BFGS direction carries its own scale
            } else {
                // backtracking along the same direction
                step *= 0.25;
                if (step < 1e-9) {
                    System.err.println("[footpred] step collapsed, resetting L-BFGS history");
                    lbfgs.reset();
                    d = negate(g);
                    step = 1e-2;
                }
            }
        }

        if (!converged) {
            System.err.println("[footpred] max iterations reached without meeting tolerance");
        }
        model.setFlat(theta);
        model.sigma2 = sigma2;
        model.iterations = last == null ? 0 : (int) last.count;
        model.finalLL = last == null ? Double.NaN : last.ll;
        model.weightedCount = last == null ? Double.NaN : last.wSum;
        model.trainedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        saveModel(conf, modelOut, model);
        if (modelOutOpt == null && runDir != null) {
            // latest snapshot keeps <workdir>/model.json valid for downstream
            // consumers and legacy scripts, while runs/<id>/ keeps the history
            IoUtils.copyFile(modelOut, new Path(workdir, "model.json"), conf);
        }
        printTeamTable(model);
        runTeamWeightDiagnostic(conf, trainingData,
                runDir != null ? runDir : workdir, registryFile, xi, friendlyWeight, refEpochDay);
        if (runDir != null) {
            System.err.println("[footpred] run dir: " + runDir);
        }
        return 0;
    }

    /**
     * Applies the shrinkage prior gradient in-place to an NLL gradient and
     * returns the penalty value to add to the NLL. Must be called while the
     * model still holds the parameters of the evaluation point (centred by
     * the projection), which the training loop guarantees.
     */
    private static double applyShrinkage(DCModel model, double[] gradNll, double sigma2) {
        if (sigma2 <= 0.0) {
            return 0.0;
        }
        Shrinkage.addPenaltyGradient(gradNll, model.attack, model.defense, sigma2);
        return Shrinkage.penaltyValue(model.attack, model.defense, sigma2);
    }

    /**
     * P3.2 diagnostic: per-team effective sample size (time-decayed,
     * friendly-discounted) written to workdir/team_weights.tsv; prints the
     * thinnest-evidence teams for the shrinkage discussion.
     */
    private void runTeamWeightDiagnostic(Configuration conf, Path trainingData, Path workdir,
                                         Path registryFile, double xi, double friendlyWeight,
                                         long refEpochDay) {
        try {
            Path out = new Path(workdir, "team_weights");
            IoUtils.deleteQuietly(out, conf);
            Job job = Job.getInstance(conf, "P3.2-team-weights");
            job.setJarByClass(FootPredDriver.class);
            job.setMapperClass(TeamWeightMapper.class);
            job.setCombinerClass(VectorSumCombiner.class);
            job.setReducerClass(TeamWeightReducer.class);
            job.setMapOutputKeyClass(IntWritable.class);
            job.setMapOutputValueClass(VectorWritable.class);
            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(Text.class);
            job.setNumReduceTasks(1);
            Configuration jc = job.getConfiguration();
            jc.setDouble("dc.xi", xi);
            jc.setDouble("dc.friendly.weight", friendlyWeight);
            jc.setLong("fp.ref.epochday", refEpochDay);
            jc.set("fp.registry.path", registryFile.toString());
            FileInputFormat.addInputPath(job, trainingData);
            FileOutputFormat.setOutputPath(job, out);
            if (!job.waitForCompletion(true)) {
                System.err.println("[footpred] WARNING: team weight diagnostic job failed"
                        + " (non-fatal)");
                return;
            }
            Path merged = new Path(workdir, "team_weights.tsv");
            IoUtils.deleteQuietly(merged, conf);
            IoUtils.mergeTextDir(out, merged, conf);

            java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
            for (String line : IoUtils.readAllLines(merged, conf)) {
                String[] f = line.split("\t");
                if (f.length >= 2) {
                    rows.add(new String[]{f[0], f[1]});
                }
            }
            java.util.Collections.sort(rows, new java.util.Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return Double.compare(Double.parseDouble(a[1]), Double.parseDouble(b[1]));
                }
            });
            System.err.println("[footpred] thinnest-evidence teams (effective weighted matches,"
                    + " full list in " + merged + "):");
            for (int i = 0; i < rows.size() && i < 10; i++) {
                System.err.printf(Locale.ROOT, "  %-24s wSum=%s%n", rows.get(i)[0], rows.get(i)[1]);
            }
        } catch (Exception e) {
            System.err.println("[footpred] WARNING: team weight diagnostic failed (non-fatal): "
                    + e.getMessage());
        }
    }

    private GradResult runGradientJob(Configuration conf, Path trainingData, Path workdir,
                                      String modelBlob, double xi, double friendlyWeight,
                                      int iter) throws Exception {
        Path gradOut = new Path(workdir, "grad_iter");
        IoUtils.deleteQuietly(gradOut, conf);

        Job job = Job.getInstance(conf, "P1-dc-gradient-iter-" + iter);
        job.setJarByClass(FootPredDriver.class);
        job.setMapperClass(DCMapper.class);
        job.setCombinerClass(VectorSumCombiner.class);
        job.setReducerClass(DCGradientReducer.class);
        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(VectorWritable.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(1);
        Configuration jc = job.getConfiguration();
        jc.set("dc.model", modelBlob);
        jc.setDouble("dc.xi", xi);
        jc.setDouble("dc.friendly.weight", friendlyWeight);
        FileInputFormat.addInputPath(job, trainingData);
        FileOutputFormat.setOutputPath(job, gradOut);
        if (!job.waitForCompletion(true)) {
            throw new IOException("DC gradient job failed at iteration " + iter);
        }
        long badTau = job.getCounters().findCounter(DCMapper.Counters.BAD_TAU).getValue();
        String line = IoUtils.readFirstNonEmptyLine(gradOut, conf);
        return GradResult.parse(line, badTau);
    }

    // ------------------------------------------------------------------ //
    //  evaluate                                                          //
    // ------------------------------------------------------------------ //

    private int evaluate(Configuration conf, Map<String, String> opts) throws Exception {
        String workdirOpt = opts.get("workdir");
        Path workdir = workdirOpt == null ? null : new Path(workdirOpt);
        Path modelPath = resolveEvalModelPath(opts, workdir, conf);
        Path runDir = resolveRunDir("evaluate", opts);
        Path evalRoot = runDir != null ? runDir : modelPath.getParent();

        Path trainingData = workdir == null ? null : new Path(workdir, "training_data");
        if (trainingData == null || !trainingData.getFileSystem(conf).exists(trainingData)) {
            // allow a custom data path for evaluation on other splits
            trainingData = new Path(
                    require(opts, "data", "evaluate needs --workdir (or --data)"));
        }
        int evalMaxGoals = (int) parseDouble(opts, "eval-max-goals", 12);

        DCModel model = readModel(conf, modelPath);
        if (runDir != null) {
            // record exactly which model produced this report
            IoUtils.copyFile(modelPath, new Path(runDir, "model_used.json"), conf);
        }
        Path evalOut = new Path(evalRoot, "evaluation");
        IoUtils.deleteQuietly(evalOut, conf);

        Job job = Job.getInstance(conf, "P1-evaluate");
        job.setJarByClass(FootPredDriver.class);
        job.setMapperClass(EvalMapper.class);
        job.setCombinerClass(VectorSumCombiner.class);
        job.setReducerClass(EvalReducer.class);
        // keep in sync with EvalMapper/EvalReducer signatures: map key is the
        // tournament category (Text), reduce output is (Text, Text)
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(VectorWritable.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(1);
        Configuration jc = job.getConfiguration();
        jc.set("dc.model", model.toBase64());
        jc.setInt("fp.eval.max.goals", evalMaxGoals);
        FileInputFormat.addInputPath(job, trainingData);
        FileOutputFormat.setOutputPath(job, evalOut);
        if (!job.waitForCompletion(true)) {
            return fail("evaluation job failed");
        }
        long valid = job.getCounters().findCounter(EvalMapper.Counters.VALID_MATCHES).getValue();
        System.err.println("[footpred] evaluation (valid matches=" + valid + "),"
                + " stratified by tournament category:");
        for (String line : IoUtils.readAllLinesInDir(evalOut, conf)) {
            System.err.println(line.replace('\t', ' '));
        }
        if (runDir != null) {
            System.err.println("[footpred] run dir: " + runDir
                    + " (report: " + evalOut + ", model: " + modelPath + ")");
        }
        return 0;
    }

    // ------------------------------------------------------------------ //
    //  baseline: B0/B1/B2 ladder vs DC (P1.1)                            //
    // ------------------------------------------------------------------ //

    private int baseline(Configuration conf, Map<String, String> opts) throws Exception {
        String workdirOpt = opts.get("workdir");
        Path workdir;
        Path modelPath;
        if (opts.containsKey("model")) {
            modelPath = new Path(opts.get("model"));
            workdir = workdirOpt != null ? new Path(workdirOpt) : modelPath.getParent();
        } else if (workdirOpt != null) {
            workdir = new Path(workdirOpt);
            modelPath = resolveEvalModelPath(opts, workdir, conf);
        } else {
            return fail("baseline needs --model or --workdir");
        }
        Path runDir = resolveRunDir("baseline", opts);
        Path runRoot = runDir != null ? runDir : workdir;
        Path trainingData = new Path(workdir, "training_data");
        IoUtils.requireInput(trainingData, conf, "training data (run prepare first)");
        IoUtils.requireInput(modelPath, conf, "model file (run train first)");
        int evalMaxGoals = (int) parseDouble(opts, "eval-max-goals", 12);

        // ---- Job 1: leakage-free train-split statistics ----
        Path statsOut = new Path(runRoot, "baseline_stats");
        IoUtils.deleteQuietly(statsOut, conf);
        Job stats = Job.getInstance(conf, "baseline-stats");
        stats.setJarByClass(FootPredDriver.class);
        stats.setMapperClass(BaselineStatsMapper.class);
        stats.setCombinerClass(VectorSumCombiner.class);
        stats.setReducerClass(BaselineStatsReducer.class);
        stats.setMapOutputKeyClass(IntWritable.class);
        stats.setMapOutputValueClass(VectorWritable.class);
        stats.setOutputKeyClass(NullWritable.class);
        stats.setOutputValueClass(Text.class);
        stats.setNumReduceTasks(1);
        FileInputFormat.addInputPath(stats, trainingData);
        FileOutputFormat.setOutputPath(stats, statsOut);
        if (!stats.waitForCompletion(true)) {
            return fail("baseline statistics job failed");
        }
        Map<String, Double> s = parseKeyValueLine(
                IoUtils.readFirstNonEmptyLine(statsOut, conf));
        double nnN = s.getOrDefault("NNN", 0.0);
        double cN = s.getOrDefault("NC", 0.0);
        if (nnN <= 0.0 && cN <= 0.0) {
            return fail("train split appears empty");
        }

        // B1: train marginal outcome frequencies by venue group
        double[] b1nn = marginal(s, "NNH", "NND", "NNA", nnN);
        double[] b1c = marginal(s, "NCH", "NCD", "NCA", cN);
        // B2: constant-intensity Poisson (method-of-moments means by group)
        double lamHnn = nnN > 0 ? Math.max(s.getOrDefault("GHNN", 0.0) / nnN, 0.1) : 1.4;
        double lamAnn = nnN > 0 ? Math.max(s.getOrDefault("GANN", 0.0) / nnN, 0.1) : 1.1;
        double lamHc = cN > 0 ? Math.max(s.getOrDefault("GHC", 0.0) / cN, 0.1) : 1.2;
        double lamAc = cN > 0 ? Math.max(s.getOrDefault("GAC", 0.0) / cN, 0.1) : 1.1;

        System.err.printf(Locale.ROOT,
                "[footpred] baseline params: B1 non-neutral H/D/A=%.3f/%.3f/%.3f"
                        + " neutral=%.3f/%.3f/%.3f | B2 lambda non-neutral=%.2f/%.2f"
                        + " neutral=%.2f/%.2f%n",
                b1nn[0], b1nn[1], b1nn[2], b1c[0], b1c[1], b1c[2],
                lamHnn, lamAnn, lamHc, lamAc);

        // ---- Job 2: score B0/B1/B2/DC on the valid split ----
        DCModel model = readModel(conf, modelPath);
        if (runDir != null) {
            IoUtils.copyFile(modelPath, new Path(runDir, "model_used.json"), conf);
        }
        Path evalOut = new Path(runRoot, "baseline_eval");
        IoUtils.deleteQuietly(evalOut, conf);
        Job eval = Job.getInstance(conf, "baseline-eval-ladder");
        eval.setJarByClass(FootPredDriver.class);
        eval.setMapperClass(BaselineEvalMapper.class);
        eval.setCombinerClass(VectorSumCombiner.class);
        eval.setReducerClass(BaselineEvalReducer.class);
        eval.setMapOutputKeyClass(Text.class);
        eval.setMapOutputValueClass(VectorWritable.class);
        eval.setOutputKeyClass(Text.class);
        eval.setOutputValueClass(Text.class);
        eval.setNumReduceTasks(1);
        Configuration jc = eval.getConfiguration();
        jc.set("dc.model", model.toBase64());
        jc.setDouble("baseline.b1.nn.pw", b1nn[0]);
        jc.setDouble("baseline.b1.nn.pd", b1nn[1]);
        jc.setDouble("baseline.b1.nn.pa", b1nn[2]);
        jc.setDouble("baseline.b1.c.pw", b1c[0]);
        jc.setDouble("baseline.b1.c.pd", b1c[1]);
        jc.setDouble("baseline.b1.c.pa", b1c[2]);
        jc.setDouble("baseline.b2.nn.lamh", lamHnn);
        jc.setDouble("baseline.b2.nn.lama", lamAnn);
        jc.setDouble("baseline.b2.c.lamh", lamHc);
        jc.setDouble("baseline.b2.c.lama", lamAc);
        jc.setInt("fp.eval.max.goals", evalMaxGoals);
        FileInputFormat.addInputPath(eval, trainingData);
        FileOutputFormat.setOutputPath(eval, evalOut);
        if (!eval.waitForCompletion(true)) {
            return fail("baseline evaluation job failed");
        }

        System.err.println("[footpred] baseline information ladder"
                + " (valid split, stratified by category):");
        for (String line : IoUtils.readAllLinesInDir(evalOut, conf)) {
            System.err.println(line.replace('\t', ' '));
        }
        if (runDir != null) {
            System.err.println("[footpred] run dir: " + runDir
                    + " (ladder: " + evalOut + ", model: " + modelPath + ")");
        }
        return 0;
    }

    private static double[] marginal(Map<String, Double> s, String h, String d, String a,
                                      double total) {
        if (total <= 0.0) {
            return new double[]{1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0};
        }
        return new double[]{
                s.getOrDefault(h, 0.0) / total,
                s.getOrDefault(d, 0.0) / total,
                s.getOrDefault(a, 0.0) / total};
    }

    private static Map<String, Double> parseKeyValueLine(String line) {
        Map<String, Double> out = new LinkedHashMap<String, Double>();
        for (String field : line.split("\t")) {
            int eq = field.indexOf('=');
            if (eq > 0) {
                out.put(field.substring(0, eq), Double.parseDouble(field.substring(eq + 1)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ //
    //  predict: Job P1.5                                                 //
    // ------------------------------------------------------------------ //

    private int predict(Configuration conf, Map<String, String> opts) throws Exception {
        Path modelPath = new Path(require(opts, "model", "predict needs --model"));
        Path fixtures = new Path(require(opts, "fixtures", "predict needs --fixtures"));
        IoUtils.requireInput(modelPath, conf, "model file");
        IoUtils.requireInput(fixtures, conf, "fixture file");
        int maxGoals = (int) parseDouble(opts, "max-goals", 10);
        String outOpt = opts.get("out");
        Path out = outOpt == null
                ? new Path(modelPath.getParent(), "predictions") : new Path(outOpt);
        IoUtils.deleteQuietly(out, conf);

        DCModel model = readModel(conf, modelPath);

        Job job = Job.getInstance(conf, "P1.5-score-matrix");
        job.setJarByClass(FootPredDriver.class);
        job.setMapperClass(ScoreMatrixMapper.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(0);
        Configuration jc = job.getConfiguration();
        jc.set("dc.model", model.toBase64());
        jc.setInt("fp.max.goals", maxGoals);
        FileInputFormat.addInputPath(job, fixtures);
        FileOutputFormat.setOutputPath(job, out);
        if (!job.waitForCompletion(true)) {
            return fail("score matrix job failed");
        }

        long unknown = job.getCounters()
                .findCounter(ScoreMatrixMapper.Counters.UNKNOWN_TEAM).getValue();
        if (unknown > 0) {
            System.err.printf(Locale.ROOT,
                    "[footpred] WARNING: %d fixtures skipped - team name not in registry"
                            + " (check spelling against model.json teams list)%n", unknown);
        }
        // echo the prediction table to the console
        FileSystem pfs = out.getFileSystem(conf);
        for (FileStatus file : pfs.listStatus(out)) {
            if (!file.getPath().getName().startsWith("part-")) {
                continue;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pfs.open(file.getPath()),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        System.err.println(line.replace('\t', ' '));
                    }
                }
            }
        }
        System.err.println("[footpred] predictions written to " + out);
        return 0;
    }

    // ------------------------------------------------------------------ //
    //  simulate: Job P5.2 (Monte-Carlo season replay)                    //
    // ------------------------------------------------------------------ //

    /**
     * Monte-Carlo season simulation in replay mode: matches before --cutoff
     * count as real played results (standing snapshot), matches at or after
     * the cutoff are simulated as independent Poisson draws with the DC
     * intensities broadcast through the configuration. The reduce side
     * appends the actual final table so simulated odds and ground truth sit
     * side by side in one report.
     */
    private int simulate(Configuration conf, Map<String, String> opts) throws Exception {
        if ("legacy".equals(opts.get("pipeline"))) return simulateLegacy(conf, opts);
        Path workdir = opts.containsKey("workdir") ? new Path(opts.get("workdir")) : null;
        Path model = resolveEvalModelPath(opts, workdir, conf);
        Path root = workdir != null ? resolveRunDir("simulate", opts)
                : new Path(model.getParent(), "runs/" + opts.get("run-id"));
        return FutureSeasonPipeline.run(conf, opts, model, root);
    }

    /** Retained for explicit regression comparisons with the original single-job implementation. */
    private int simulateLegacy(Configuration conf, Map<String, String> opts) throws Exception {
        Path input = new Path(require(opts, "input",
                "simulate needs --input (results.csv-style league csv on HDFS)"));
        IoUtils.requireInput(input, conf, "simulate input (league results csv)");

        String workdirOpt = opts.get("workdir");
        Path workdir = workdirOpt == null ? null : new Path(workdirOpt);
        Path modelPath = resolveEvalModelPath(opts, workdir, conf);
        IoUtils.requireInput(modelPath, conf, "model file");
        DCModel model = readModel(conf, modelPath);

        String leagueFilter = opts.get("league");
        String seasonStart = opts.getOrDefault("season-start", "2025-08-01");
        String cutoffDate = opts.getOrDefault("cutoff", "2026-01-01");
        String seasonEnd = opts.getOrDefault("season-end", "2026-06-30");
        long startEpoch = LocalDate.parse(seasonStart).toEpochDay();
        long cutoffEpoch = LocalDate.parse(cutoffDate).toEpochDay();
        long endEpoch = LocalDate.parse(seasonEnd).toEpochDay();
        if (cutoffEpoch <= startEpoch || endEpoch < cutoffEpoch) {
            return fail("need season-start < cutoff <= season-end, got " + seasonStart
                    + " < " + cutoffDate + " <= " + seasonEnd);
        }
        int totalSims = (int) parseDouble(opts, "total-sims", 10000);
        int maps = (int) parseDouble(opts, "maps", 2);
        long seed = (long) parseDouble(opts, "seed", 42);
        int topPlaces = (int) parseDouble(opts, "top-places", 4);
        String relOpt = opts.get("relegation-places");
        int relOverride = relOpt != null && !"auto".equals(relOpt)
                ? Integer.parseInt(relOpt) : -1;
        if (totalSims <= 0 || maps <= 0) {
            return fail("--total-sims and --maps must be positive");
        }
        maps = Math.min(maps, totalSims);

        Path runDir = resolveRunDir("simulate", opts);
        Path runRoot = runDir != null ? runDir : modelPath.getParent();
        Path outDir = opts.containsKey("out")
                ? new Path(opts.get("out")) : new Path(runRoot, "simulation");
        Path realTablePath = new Path(outDir.getParent(), "real_final_table.tsv");

        // ---- model roster lookup (model.json is the name contract) ----
        Map<String, Integer> idxByName = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < model.nTeams; i++) {
            idxByName.put(model.teamNames.get(i), i);
        }

        // ---- parse the replay window ----
        Map<String, LeagueBuild> builds = new LinkedHashMap<String, LeagueBuild>();
        long inWindow = 0;
        long unknownTeam = 0;
        for (String line : IoUtils.readAllLines(input, conf)) {
            RawMatch m = MatchParser.parse(line);
            if (m == null) {
                continue; // header row / malformed
            }
            if (m.epochDay < startEpoch || m.epochDay > endEpoch) {
                continue; // outside the replay window
            }
            if (leagueFilter != null && !leagueFilter.equals(m.tournament)) {
                continue;
            }
            inWindow++;
            if (!idxByName.containsKey(m.homeTeam) || !idxByName.containsKey(m.awayTeam)) {
                unknownTeam++;
                continue;
            }
            LeagueBuild b = builds.get(m.tournament);
            if (b == null) {
                b = new LeagueBuild();
                builds.put(m.tournament, b);
            }
            if (!b.localIdx.containsKey(m.homeTeam)) {
                b.localIdx.put(m.homeTeam, b.teamList.size());
                b.teamList.add(m.homeTeam);
            }
            if (!b.localIdx.containsKey(m.awayTeam)) {
                b.localIdx.put(m.awayTeam, b.teamList.size());
                b.teamList.add(m.awayTeam);
            }
            if (m.epochDay < cutoffEpoch) {
                b.played.add(m);
            } else {
                b.remaining.add(m);
            }
        }
        if (builds.isEmpty()) {
            return fail("no in-window matches kept (in-window rows=" + inWindow
                    + ", unknown-team skips=" + unknownTeam
                    + "); check --season-start/--season-end/--league and --input");
        }
        if (unknownTeam > 0) {
            System.err.printf(Locale.ROOT,
                    "[footpred] WARNING: %d in-window matches skipped - team not in"
                            + " the model roster (%s)%n", unknownTeam, modelPath);
        }

        // ---- standings snapshot at cutoff + actual table + fixtures ----
        List<SimContext.League> leagues = new ArrayList<SimContext.League>();
        List<SimContext.Fixture> fixtures = new ArrayList<SimContext.Fixture>();
        Map<String, LeagueMeta> metaByName = new LinkedHashMap<String, LeagueMeta>();
        for (Map.Entry<String, LeagueBuild> entry : builds.entrySet()) {
            String leagueName = entry.getKey();
            LeagueBuild b = entry.getValue();
            if (b.teamList.size() < 2) {
                return fail("league " + leagueName + " has " + b.teamList.size()
                        + " teams - too few to simulate");
            }
            int rel = relOverride > 0 ? relOverride : autoRelegationPlaces(leagueName);
            LeagueTable atCutoff = new LeagueTable(leagueName, b.teamList);
            LeagueTable actual = new LeagueTable(leagueName, b.teamList);
            for (RawMatch m : b.played) {
                atCutoff.applyResult(b.localIdx.get(m.homeTeam),
                        b.localIdx.get(m.awayTeam), m.homeScore, m.awayScore);
                actual.applyResult(b.localIdx.get(m.homeTeam),
                        b.localIdx.get(m.awayTeam), m.homeScore, m.awayScore);
            }
            for (RawMatch m : b.remaining) {
                actual.applyResult(b.localIdx.get(m.homeTeam),
                        b.localIdx.get(m.awayTeam), m.homeScore, m.awayScore);
                fixtures.add(new SimContext.Fixture(leagueName,
                        b.localIdx.get(m.homeTeam), b.localIdx.get(m.awayTeam),
                        model.lambdaHome(idxByName.get(m.homeTeam),
                                idxByName.get(m.awayTeam), m.neutral),
                        model.lambdaAway(idxByName.get(m.homeTeam),
                                idxByName.get(m.awayTeam)),
                        m.epochDay));
            }
            leagues.add(new SimContext.League(leagueName, rel, topPlaces, b.teamList,
                    atCutoff.points, atCutoff.goalsFor, atCutoff.goalsAgainst,
                    atCutoff.played, actual.points, actual.ranks()));
            metaByName.put(leagueName, new LeagueMeta(b.teamList.size(), rel,
                    b.played.size(), b.remaining.size()));
            System.err.printf(Locale.ROOT,
                    "[footpred] league=%s teams=%d played=%d remaining=%d"
                            + " relegationPlaces=%d topPlaces=%d%n",
                    leagueName, b.teamList.size(), b.played.size(),
                    b.remaining.size(), rel, topPlaces);
        }
        java.util.Collections.sort(fixtures, new Comparator<SimContext.Fixture>() {
            @Override
            public int compare(SimContext.Fixture a, SimContext.Fixture b) {
                int byDay = Long.compare(a.epochDay, b.epochDay);
                if (byDay != 0) {
                    return byDay;
                }
                return a.league.compareTo(b.league);
            }
        });
        int playedTotal = 0;
        int remainingTotal = 0;
        for (LeagueMeta meta : metaByName.values()) {
            playedTotal += meta.played;
            remainingTotal += meta.remaining;
        }
        System.err.printf(Locale.ROOT,
                "[footpred] simulate: model=%s cutoff=%s (%d played / %d remaining"
                        + " across %d leagues), %d seasons on %d mappers, seed=%d%n",
                modelPath, cutoffDate, playedTotal, remainingTotal,
                leagues.size(), totalSims, maps, seed);

        // ---- broadcast context + seed-segment file + Job P5.2 ----
        String blob = new SimContext(leagues, fixtures).encode();
        FileSystem fs = runRoot.getFileSystem(conf);
        // NOTE: FileInputFormat silently skips paths starting with '_' or '.',
        // so the seed-segment file must not use the underscore prefix.
        Path seeds = new Path(runRoot, "sim-seeds-" + timestampId("tmp"));
        try {
            try (FSDataOutputStream out = fs.create(seeds, true)) {
                int base = totalSims / maps;
                int rest = totalSims % maps;
                for (int i = 0; i < maps; i++) {
                    int nSims = base + (i < rest ? 1 : 0);
                    out.write((i + "\t" + nSims + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }

            IoUtils.deleteQuietly(outDir, conf);
            Job job = Job.getInstance(conf, "P5.2-season-simulation");
            job.setJarByClass(FootPredDriver.class);
            job.setMapperClass(MonteCarloMapper.class);
            job.setCombinerClass(VectorSumCombiner.class);
            job.setReducerClass(MonteCarloReducer.class);
            // keep in sync with MonteCarloMapper/MonteCarloReducer signatures:
            // map key is the "league \t team" Text, reduce output is (Text, Text)
            job.setMapOutputKeyClass(Text.class);
            job.setMapOutputValueClass(VectorWritable.class);
            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(Text.class);
            job.setNumReduceTasks(1);
            job.setInputFormatClass(NLineInputFormat.class);
            NLineInputFormat.setNumLinesPerSplit(job, 1);
            Configuration jc = job.getConfiguration();
            jc.set("sim.context", blob);
            jc.setLong("sim.seed", seed);
            FileInputFormat.addInputPath(job, seeds);
            FileOutputFormat.setOutputPath(job, outDir);
            if (!job.waitForCompletion(true)) {
                return fail("season simulation job failed");
            }
            long simsRun = job.getCounters()
                    .findCounter(MonteCarloMapper.Counters.SIMS_RUN).getValue();
            System.err.printf(Locale.ROOT,
                    "[footpred] simulation done: %d seasons (requested %d)%n",
                    simsRun, totalSims);

            // ---- post-process: actual final table + odds summary ----
            writeActualFinalTable(builds, realTablePath, conf);
            printSimulationSummary(IoUtils.readAllLinesInDir(outDir, conf), metaByName);
        } finally {
            try {
                fs.delete(seeds, false);
            } catch (IOException ignored) {
                // best-effort cleanup of the seed-segment scratch file
            }
        }
        System.err.println("[footpred] simulation output: " + outDir);
        System.err.println("[footpred] actual final table: " + realTablePath);
        if (runDir != null) {
            System.err.println("[footpred] run dir: " + runDir);
        }
        return 0;
    }

    // ------------------------------------------------------------------ //
    //  history: Module 5 analyses P6.5-P6.8                              //
    // ------------------------------------------------------------------ //

    /**
     * Module 5 historical analyses. Each analysis runs as one (or two, for
     * the JSON-based ones) standard aggregate MapReduce jobs and writes TSV
     * reports consumed directly by the service layer / frontend.
     *
     *   p67  big-scoreline distribution: input = results.csv-style CSVs
     *        (source label derived from the input path), output rows
     *        source/decade/bucket/count/pct
     *   p66  league style evolution: input = results_leagues_full.tsv
     *        (23 cols incl. technical stats), output per (league, season)
     *        per-game averages with empty fields where a stat is uncovered
     *   p68  nationality diversity: input = staged lineups dir
     *        statsbomb/lineups/&lt;league&gt;/&lt;season&gt;/*.json; job 1
     *        flattens whole-file JSON, job 2 aggregates distinct players by
     *        (league, season, country) + diversity summary rows
     *   p65  career curves: input = worldcup player_appearances.csv +
     *        worldcup goals.csv + goalscorers.csv (job 1), optional
     *        --events staged dir for the target player's club line (job 2)
     */
    private int history(Configuration conf, Map<String, String> opts) throws Exception {
        String analysis = require(opts, "analysis", "history needs --analysis p65|p66|p67|p68");
        Path[] inputs = parseInputList(
                require(opts, "input", "history needs --input (comma-separated HDFS paths)"));
        for (Path p : inputs) {
            IoUtils.requireInput(p, conf, "history input");
        }
        Path runDir = resolveRunDir("history", opts);
        Path root;
        if (opts.containsKey("out")) {
            root = new Path(opts.get("out"));
        } else if (runDir != null) {
            root = runDir;
        } else {
            return fail("history needs --out or --workdir (for run archiving)");
        }
        if ("p67".equals(analysis)) {
            return historyScoreDiff(conf, inputs, new Path(root, "p67"));
        }
        if ("p66".equals(analysis)) {
            return historyLeagueStyle(conf, inputs, new Path(root, "p66"));
        }
        if ("p68".equals(analysis)) {
            return historyNationality(conf, inputs, new Path(root, "p68"));
        }
        if ("p65".equals(analysis)) {
            return historyCareer(conf, inputs, new Path(root, "p65"), opts);
        }
        return fail("unknown --analysis " + analysis + " (expected p65|p66|p67|p68)");
    }

    /** Parses a comma-separated --input list into HDFS paths. */
    private static Path[] parseInputList(String value) {
        String[] parts = value.split(",");
        Path[] paths = new Path[parts.length];
        for (int i = 0; i < parts.length; i++) {
            paths[i] = new Path(parts[i].trim());
        }
        return paths;
    }

    private int historyScoreDiff(Configuration conf, Path[] inputs, Path out) throws Exception {
        IoUtils.deleteQuietly(out, conf);
        Job job = Job.getInstance(conf, "P6.7-score-diff-distribution");
        job.setJarByClass(FootPredDriver.class);
        job.setMapperClass(ScoreDiffMapper.class);
        job.setCombinerClass(VectorSumCombiner.class);
        job.setReducerClass(ScoreDiffReducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(VectorWritable.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(1); // streaming decade-group flush needs one reduce
        for (Path p : inputs) {
            FileInputFormat.addInputPath(job, p);
        }
        FileOutputFormat.setOutputPath(job, out);
        if (!job.waitForCompletion(true)) {
            return fail("P6.7 score-diff job failed");
        }
        long matches = job.getCounters().findCounter(ScoreDiffMapper.Counters.MATCHES).getValue();
        long bad = job.getCounters().findCounter(ScoreDiffMapper.Counters.BAD_LINE).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P6.7 done: %d matches parsed (%d skipped), distribution:%n",
                matches, bad);
        for (String line : IoUtils.readAllLinesInDir(out, conf)) {
            System.err.println(line.replace('\t', ' '));
        }
        System.err.println("[footpred] P6.7 output: " + out);
        return 0;
    }

    private int historyLeagueStyle(Configuration conf, Path[] inputs, Path out) throws Exception {
        IoUtils.deleteQuietly(out, conf);
        Job job = Job.getInstance(conf, "P6.6-league-style-evolution");
        job.setJarByClass(FootPredDriver.class);
        job.setMapperClass(LeagueStyleMapper.class);
        job.setCombinerClass(VectorSumCombiner.class);
        job.setReducerClass(LeagueStyleReducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(VectorWritable.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(1);
        for (Path p : inputs) {
            FileInputFormat.addInputPath(job, p);
        }
        FileOutputFormat.setOutputPath(job, out);
        if (!job.waitForCompletion(true)) {
            return fail("P6.6 league-style job failed");
        }
        long rows = job.getCounters().findCounter(LeagueStyleMapper.Counters.ROWS).getValue();
        long bad = job.getCounters().findCounter(LeagueStyleMapper.Counters.BAD_LINE).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P6.6 done: %d rows parsed (%d skipped), per-season averages:%n",
                rows, bad);
        for (String line : IoUtils.readAllLinesInDir(out, conf)) {
            System.err.println(line.replace('\t', ' '));
        }
        System.err.println("[footpred] P6.6 output: " + out);
        return 0;
    }

    private int historyNationality(Configuration conf, Path[] inputs, Path out)
            throws Exception {
        // ---- Job P6.8a: flatten whole-file lineups JSON (map-only) ----
        Path flat = new Path(out, "lineups_flat");
        IoUtils.deleteQuietly(flat, conf);
        Job step1 = Job.getInstance(conf, "P6.8a-lineups-flatten");
        step1.setJarByClass(FootPredDriver.class);
        step1.setMapperClass(LineupFlattenMapper.class);
        step1.setMapOutputKeyClass(NullWritable.class);
        step1.setMapOutputValueClass(Text.class);
        step1.setOutputKeyClass(NullWritable.class);
        step1.setOutputValueClass(Text.class);
        step1.setNumReduceTasks(0);
        step1.setInputFormatClass(WholeFileInputFormat.class);
        // recursive listing: files live in <league>/<season>/ subdirectories,
        // and pack the ~4200 small JSON files into a handful of map tasks
        FileInputFormat.setInputDirRecursive(step1, true);
        step1.getConfiguration().setLong(FileInputFormat.SPLIT_MAXSIZE, 64L * 1024 * 1024);
        for (Path p : inputs) {
            FileInputFormat.addInputPath(step1, p);
        }
        FileOutputFormat.setOutputPath(step1, flat);
        if (!step1.waitForCompletion(true)) {
            return fail("P6.8a lineups flatten job failed");
        }
        long players = step1.getCounters().findCounter(LineupFlattenMapper.Counters.PLAYERS)
                .getValue();
        long noCountry = step1.getCounters()
                .findCounter(LineupFlattenMapper.Counters.NO_COUNTRY).getValue();
        long badJson = step1.getCounters()
                .findCounter(LineupFlattenMapper.Counters.BAD_JSON).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P6.8a done: %d player records flattened"
                        + " (missing country=%d, bad json=%d)%n",
                players, noCountry, badJson);

        // ---- Job P6.8b: distinct players by (league, season, country) ----
        Path agg = new Path(out, "nationality");
        IoUtils.deleteQuietly(agg, conf);
        Job step2 = Job.getInstance(conf, "P6.8b-nationality-diversity");
        step2.setJarByClass(FootPredDriver.class);
        step2.setMapperClass(NationalityMapper.class);
        step2.setCombinerClass(VectorSumCombiner.class);
        step2.setReducerClass(NationalityReducer.class);
        step2.setMapOutputKeyClass(Text.class);
        step2.setMapOutputValueClass(VectorWritable.class);
        step2.setOutputKeyClass(NullWritable.class);
        step2.setOutputValueClass(Text.class);
        step2.setNumReduceTasks(1); // streaming league-season flush needs one reduce
        FileInputFormat.addInputPath(step2, flat);
        FileOutputFormat.setOutputPath(step2, agg);
        if (!step2.waitForCompletion(true)) {
            return fail("P6.8b nationality job failed");
        }
        long rows = step2.getCounters().findCounter(NationalityMapper.Counters.ROWS).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P6.8b done: %d flattened rows aggregated, league-season"
                        + " diversity summaries:%n", rows);
        for (String line : IoUtils.readAllLinesInDir(agg, conf)) {
            if (line.startsWith("S\t")) {
                System.err.println(line.replace('\t', ' '));
            }
        }
        System.err.println("[footpred] P6.8 outputs: " + agg + " (D=per-country detail,"
                + " S=diversity summary) + " + flat);
        return 0;
    }

    private int historyCareer(Configuration conf, Path[] inputs, Path out,
                              Map<String, String> opts) throws Exception {
        // ---- Job P6.5a: CSV sources (worldcup apps/goals + goalscorers) ----
        Path careerOut = new Path(out, "career");
        IoUtils.deleteQuietly(careerOut, conf);
        Job step1 = Job.getInstance(conf, "P6.5a-career-csv");
        step1.setJarByClass(FootPredDriver.class);
        step1.setMapperClass(CareerCsvMapper.class);
        step1.setCombinerClass(VectorSumCombiner.class);
        step1.setReducerClass(CareerReducer.class);
        step1.setMapOutputKeyClass(Text.class);
        step1.setMapOutputValueClass(VectorWritable.class);
        step1.setOutputKeyClass(NullWritable.class);
        step1.setOutputValueClass(Text.class);
        step1.setNumReduceTasks(1);
        for (Path p : inputs) {
            FileInputFormat.addInputPath(step1, p);
        }
        FileOutputFormat.setOutputPath(step1, careerOut);
        if (!step1.waitForCompletion(true)) {
            return fail("P6.5a career csv job failed");
        }
        long apps = step1.getCounters().findCounter(CareerCsvMapper.Counters.WC_APP_ROWS)
                .getValue();
        long wcGoals = step1.getCounters().findCounter(CareerCsvMapper.Counters.WC_GOAL_ROWS)
                .getValue();
        long intlGoals = step1.getCounters()
                .findCounter(CareerCsvMapper.Counters.INTL_GOAL_ROWS).getValue();
        long bad = step1.getCounters().findCounter(CareerCsvMapper.Counters.BAD_LINE).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P6.5a done: wc apps=%d wc goals=%d intl goals=%d (%d bad lines),"
                        + " output: %s%n", apps, wcGoals, intlGoals, bad, careerOut);

        // ---- Job P6.5b (optional): target player's club line from events ----
        String eventsOpt = opts.get("events");
        if (eventsOpt == null) {
            return 0;
        }
        Path events = new Path(eventsOpt);
        IoUtils.requireInput(events, conf, "P6.5b staged events dir (--events)");
        long playerId = (long) parseDouble(opts, "player-id", 5503.0);
        Path messiOut = new Path(out, "messi_club");
        IoUtils.deleteQuietly(messiOut, conf);
        Job step2 = Job.getInstance(conf, "P6.5b-career-events");
        step2.setJarByClass(FootPredDriver.class);
        step2.setMapperClass(MessiEventsMapper.class);
        step2.setCombinerClass(VectorSumCombiner.class);
        step2.setReducerClass(MessiEventsReducer.class);
        step2.setMapOutputKeyClass(Text.class);
        step2.setMapOutputValueClass(VectorWritable.class);
        step2.setOutputKeyClass(NullWritable.class);
        step2.setOutputValueClass(Text.class);
        step2.setNumReduceTasks(1);
        step2.setInputFormatClass(WholeFileInputFormat.class);
        FileInputFormat.setInputDirRecursive(step2, true);
        step2.getConfiguration().setLong(FileInputFormat.SPLIT_MAXSIZE, 64L * 1024 * 1024);
        step2.getConfiguration().setLong("career.player.id", playerId);
        FileInputFormat.addInputPath(step2, events);
        FileOutputFormat.setOutputPath(step2, messiOut);
        if (!step2.waitForCompletion(true)) {
            return fail("P6.5b career events job failed");
        }
        long targetApps = step2.getCounters()
                .findCounter(MessiEventsMapper.Counters.TARGET_APPS).getValue();
        System.err.printf(Locale.ROOT,
                "[footpred] P6.5b done: player %d appeared in %d staged matches,"
                        + " club season line:%n", playerId, targetApps);
        for (String line : IoUtils.readAllLinesInDir(messiOut, conf)) {
            System.err.println(line.replace('\t', ' '));
        }
        System.err.println("[footpred] P6.5 outputs: " + careerOut + " + " + messiOut);
        return 0;
    }

    /**
     * Prints the per-league odds digest: title race (top 5 by champion
     * probability), relegation battle (top 3), simulated vs actual champion
     * and the rank correlation between simulated and actual tables.
     *
     * Row shape (see MonteCarloReducer):
     * league, team, avgPoints, sdPoints, avgRank, pChampion, pTop,
     * pRelegated, realRank, realPoints, rankHist
     */
    private static void printSimulationSummary(List<String> rows,
                                                Map<String, LeagueMeta> metaByName) {
        Map<String, List<String[]>> byLeague = new LinkedHashMap<String, List<String[]>>();
        for (String row : rows) {
            String[] f = row.split("\t");
            if (f.length < 10) {
                continue;
            }
            List<String[]> list = byLeague.get(f[0]);
            if (list == null) {
                list = new ArrayList<String[]>();
                byLeague.put(f[0], list);
            }
            list.add(f);
        }
        for (Map.Entry<String, List<String[]>> entry : byLeague.entrySet()) {
            String leagueName = entry.getKey();
            List<String[]> list = entry.getValue();
            LeagueMeta meta = metaByName.get(leagueName);
            int n = list.size();

            List<String[]> byChamp = new ArrayList<String[]>(list);
            java.util.Collections.sort(byChamp, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    int byP = Double.compare(
                            Double.parseDouble(b[5]), Double.parseDouble(a[5]));
                    if (byP != 0) {
                        return byP;
                    }
                    return Double.compare(
                            Double.parseDouble(a[4]), Double.parseDouble(b[4]));
                }
            });
            System.err.printf(Locale.ROOT,
                    "[footpred] === %s: title race (top %d of %d) ===%n",
                    leagueName, Math.min(5, n), n);
            for (int i = 0; i < 5 && i < n; i++) {
                String[] f = byChamp.get(i);
                System.err.printf(Locale.ROOT,
                        "  %-24s pChampion=%.4f pTop=%.4f avgPoints=%.1f avgRank=%.2f"
                                + " (actualRank=%s, actualPoints=%s)%n",
                        f[1], Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                        Double.parseDouble(f[2]), Double.parseDouble(f[4]), f[8], f[9]);
            }

            List<String[]> byRel = new ArrayList<String[]>(list);
            java.util.Collections.sort(byRel, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    int byP = Double.compare(
                            Double.parseDouble(b[7]), Double.parseDouble(a[7]));
                    if (byP != 0) {
                        return byP;
                    }
                    return Double.compare(
                            Double.parseDouble(a[4]), Double.parseDouble(b[4]));
                }
            });
            System.err.printf(Locale.ROOT,
                    "[footpred] %s: relegation battle (top 3)%n", leagueName);
            for (int i = 0; i < 3 && i < n; i++) {
                String[] f = byRel.get(i);
                System.err.printf(Locale.ROOT,
                        "  %-24s pRelegated=%.4f avgRank=%.2f (actualRank=%s)%n",
                        f[1], Double.parseDouble(f[7]), Double.parseDouble(f[4]), f[8]);
            }

            String simChampion = byChamp.get(0)[1];
            double simChampionP = Double.parseDouble(byChamp.get(0)[5]);
            String actualChampion = null;
            StringBuilder actualRelegated = new StringBuilder();
            int relPlaces = meta != null ? meta.relegationPlaces : 3;
            for (String[] f : list) {
                int realRank = Integer.parseInt(f[8]);
                if (realRank == 1) {
                    actualChampion = f[1];
                }
                if (realRank > n - relPlaces) {
                    if (actualRelegated.length() > 0) {
                        actualRelegated.append(", ");
                    }
                    actualRelegated.append(f[1]);
                }
            }
            System.err.printf(Locale.ROOT,
                    "[footpred] %s champion: simulated %s (p=%.4f) | actual %s%n",
                    leagueName, simChampion, simChampionP, actualChampion);
            System.err.printf(Locale.ROOT,
                    "[footpred] %s relegated (actual): %s%n",
                    leagueName, actualRelegated.length() > 0
                            ? actualRelegated.toString() : "(none in window)");

            double[] avgRank = new double[n];
            double[] actualRank = new double[n];
            for (int i = 0; i < n; i++) {
                avgRank[i] = Double.parseDouble(list.get(i)[4]);
                actualRank[i] = Integer.parseInt(list.get(i)[8]);
            }
            double corr = pearson(avgRank, actualRank);
            if (!Double.isNaN(corr)) {
                System.err.printf(Locale.ROOT,
                        "[footpred] %s corr(avgRank, actualRank) = %.3f%n",
                        leagueName, corr);
            }
        }
    }

    /** Writes the actual final table of every league for downstream charting. */
    private static void writeActualFinalTable(Map<String, LeagueBuild> builds,
                                              Path dst, Configuration conf) throws IOException {
        FileSystem fs = dst.getFileSystem(conf);
        try (FSDataOutputStream out = fs.create(dst, true)) {
            out.write(("# actual final table of the replay season"
                    + " (points, goal difference, goals for tie-break)\n"
                    + "# league\tteam\trank\tpoints\tplayed\tgoalsFor\tgoalsAgainst"
                    + "\tgoalDiff\n").getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, LeagueBuild> entry : builds.entrySet()) {
                String leagueName = entry.getKey();
                LeagueBuild b = entry.getValue();
                LeagueTable table = new LeagueTable(leagueName, b.teamList);
                for (RawMatch m : b.played) {
                    table.applyResult(b.localIdx.get(m.homeTeam),
                            b.localIdx.get(m.awayTeam), m.homeScore, m.awayScore);
                }
                for (RawMatch m : b.remaining) {
                    table.applyResult(b.localIdx.get(m.homeTeam),
                            b.localIdx.get(m.awayTeam), m.homeScore, m.awayScore);
                }
                int[] ranks = table.ranks();
                for (int i = 0; i < b.teamList.size(); i++) {
                    String row = leagueName + "\t" + b.teamList.get(i)
                            + "\t" + ranks[i]
                            + "\t" + table.points[i]
                            + "\t" + table.played[i]
                            + "\t" + table.goalsFor[i]
                            + "\t" + table.goalsAgainst[i]
                            + "\t" + (table.goalsFor[i] - table.goalsAgainst[i]) + "\n";
                    out.write(row.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    /**
     * Direct relegation spots by league (18-team leagues relegate 2, 20-team
     * leagues 3; playoff places excluded; override with --relegation-places).
     */
    private static int autoRelegationPlaces(String league) {
        if ("Bundesliga".equals(league) || "Ligue 1".equals(league)) {
            return 2;
        }
        return 3;
    }

    /** Pearson correlation; NaN when either side is constant or n < 2. */
    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        if (n < 2 || n != y.length) {
            return Double.NaN;
        }
        double mx = 0.0;
        double my = 0.0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0.0;
        double sxx = 0.0;
        double syy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx <= 0.0 || syy <= 0.0) {
            return Double.NaN;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    /** One league's parsed replay window: roster + played/remaining matches. */
    private static final class LeagueBuild {
        final LinkedHashMap<String, Integer> localIdx = new LinkedHashMap<String, Integer>();
        final List<String> teamList = new ArrayList<String>();
        final List<RawMatch> played = new ArrayList<RawMatch>();
        final List<RawMatch> remaining = new ArrayList<RawMatch>();
    }

    /** Per-league summary numbers used by the odds digest. */
    private static final class LeagueMeta {
        final int nTeams;
        final int relegationPlaces;
        final int played;
        final int remaining;

        LeagueMeta(int nTeams, int relegationPlaces, int played, int remaining) {
            this.nTeams = nTeams;
            this.relegationPlaces = relegationPlaces;
            this.played = played;
            this.remaining = remaining;
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                           //
    // ------------------------------------------------------------------ //

    private static final class GradResult {
        double ll;
        double wSum;
        long count;
        double[] grad;
        long badTau;

        static GradResult parse(String line, long badTau) {
            GradResult r = new GradResult();
            r.badTau = badTau;
            String[] fields = line.split("\t");
            for (String field : fields) {
                if (field.startsWith("LL=")) {
                    r.ll = Double.parseDouble(field.substring(3));
                } else if (field.startsWith("WSUM=")) {
                    r.wSum = Double.parseDouble(field.substring(5));
                } else if (field.startsWith("COUNT=")) {
                    r.count = Long.parseLong(field.substring(6));
                } else if (field.startsWith("GRAD=")) {
                    String[] tokens = field.substring(5).trim().split("\\s+");
                    r.grad = new double[tokens.length];
                    for (int i = 0; i < tokens.length; i++) {
                        r.grad[i] = Double.parseDouble(tokens[i]);
                    }
                }
            }
            if (r.grad == null) {
                throw new IllegalStateException("Gradient line missing GRAD field: " + line);
            }
            return r;
        }
    }

    private void saveModel(Configuration conf, Path modelOut, DCModel model) throws IOException {
        FileSystem fs = modelOut.getFileSystem(conf);
        if (fs.exists(modelOut)) {
            fs.delete(modelOut, false);
        }
        try (FSDataOutputStream out = fs.create(modelOut, true)) {
            out.write(model.toJsonBytes());
        }
        System.err.println("[footpred] model saved: " + modelOut
                + " (homeAdv=" + String.format(Locale.ROOT, "%.4f", model.homeAdv)
                + ", rho=" + String.format(Locale.ROOT, "%.4f", model.rho) + ")");
    }

    private void printTeamTable(DCModel model) {
        int n = Math.min(model.nTeams, 10);
        Integer[] byAttack = new Integer[model.nTeams];
        Integer[] byDefense = new Integer[model.nTeams];
        for (int i = 0; i < model.nTeams; i++) {
            byAttack[i] = i;
            byDefense[i] = i;
        }
        Arrays.sort(byAttack, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(model.attack[b], model.attack[a]); // best attack first
            }
        });
        Arrays.sort(byDefense, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(model.defense[a], model.defense[b]); // best defense first
            }
        });
        System.err.println("[footpred] top attack:");
        for (int i = 0; i < n; i++) {
            int idx = byAttack[i];
            System.err.printf(Locale.ROOT, "  %-24s attack=%+.3f%n",
                    model.teamNames.get(idx), model.attack[idx]);
        }
        System.err.println("[footpred] top defense (lower = stronger):");
        for (int i = 0; i < n; i++) {
            int idx = byDefense[i];
            System.err.printf(Locale.ROOT, "  %-24s defense=%+.3f%n",
                    model.teamNames.get(idx), model.defense[idx]);
        }
    }

    private DCModel readModel(Configuration conf, Path path) throws IOException {
        FileSystem fs = path.getFileSystem(conf);
        try (InputStream in = fs.open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                bos.write(buf, 0, read);
            }
            return DCModel.fromJson(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static double[] negate(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = -v[i];
        }
        return out;
    }

    private static double maxAbs(double[] v) {
        double m = 0.0;
        for (double x : v) {
            m = Math.max(m, Math.abs(x));
        }
        return m;
    }

    private static int fail(String message) {
        System.err.println("[footpred] FAILED: " + message);
        return 2;
    }

    private static Map<String, String> parseOptions(String[] args, int from) {
        Map<String, String> opts = new LinkedHashMap<String, String>();
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Expected --option, got: " + arg);
            }
            String key = arg.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Option --" + key + " needs a value");
            }
            opts.put(key, args[++i]);
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String key, String message) {
        String value = opts.get(key);
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static double parseDouble(Map<String, String> opts, String key, double dflt) {
        String value = opts.get(key);
        return value == null ? dflt : Double.parseDouble(value);
    }
}
