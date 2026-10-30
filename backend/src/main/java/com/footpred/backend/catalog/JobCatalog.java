package com.footpred.backend.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of every MapReduce command exposed by the unified platform.
 *
 * <p>Built from the actual driver sources, not from the design notes: the
 * partner package dispatches on {@code FootPredDriver.run} with seven named
 * sub-commands, the handoff package translates {@code club-*} / {@code score} /
 * {@code champions} onto {@code FootballMRDriver} and validates a fixed
 * positional argument count. Anything not listed here cannot be submitted, which
 * keeps the API surface equal to the verified surface.
 */
public final class JobCatalog {

    public static final String JAR_PARTNER = "partner";
    public static final String JAR_HANDOFF_M2 = "module2";
    public static final String JAR_HANDOFF_M3 = "module3";
    public static final String JAR_HANDOFF_M5 = "module5";

    private static final Map<String, JobSpec> SPECS;

    static {
        Map<String, JobSpec> specs = new LinkedHashMap<String, JobSpec>();

        // ---- partner package: modules 1 and 4, P6.5-P6.8 ----
        specs.put("prepare", JobSpec.builder("prepare")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.SCORE_PREDICTION)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("input").describedAs("results.csv on HDFS").build())
                .argument(arg("workdir").describedAs("module work directory").build())
                .argument(arg("former-names").optional(null).describedAs("former_names.csv").build())
                .argument(arg("start-date").optional("2016-01-01").describedAs("training window start").build())
                .argument(arg("valid-date").optional("2025-07-01").describedAs("walk-forward split date").build())
                .describedAs("P0: team registry + walk-forward training data")
                .expectedSeconds(30)
                .build());

        specs.put("train", JobSpec.builder("train")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.SCORE_PREDICTION)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("workdir").build())
                .argument(arg("model-out").optional(null).build())
                .argument(arg("half-life-days").optional("730").build())
                .argument(arg("friendly-weight").optional("0.4").build())
                .argument(arg("sigma2").optional(null).describedAs("shrinkage prior variance, 0=off").build())
                .argument(arg("max-iter").optional("60").build())
                .argument(arg("lbfgs-history").optional("8").build())
                .argument(arg("ref-date").optional(null).build())
                .argument(arg("run-id").optional(null).build())
                .describedAs("P1: Dixon-Coles L-BFGS iterations (one Job per round)")
                .retrains(true)
                .expectedSeconds(600)
                .build());

        specs.put("evaluate", JobSpec.builder("evaluate")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.SCORE_PREDICTION)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("workdir").build())
                .argument(arg("model").optional(null).build())
                .argument(arg("eval-max-goals").optional("12").build())
                .argument(arg("run-id").optional(null).build())
                .describedAs("P1: stratified RPS / LogLoss / accuracy report")
                .expectedSeconds(40)
                .build());

        specs.put("baseline", JobSpec.builder("baseline")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.SCORE_PREDICTION)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("workdir").build())
                .argument(arg("model").optional(null).build())
                .argument(arg("run-id").optional(null).build())
                .describedAs("B0/B1/B2 information ladder against DC")
                .expectedSeconds(40)
                .build());

        specs.put("predict", JobSpec.builder("predict")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.SCORE_PREDICTION)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("model").build())
                .argument(arg("fixtures").build())
                .argument(arg("out").optional(null).build())
                .argument(arg("max-goals").optional("10").build())
                .describedAs("P1.5: DC score matrix, map-only, one request per Job")
                .expectedSeconds(28)
                .build());

        specs.put("simulate", JobSpec.builder("simulate")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.SEASON_SIMULATION)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("input").describedAs("league results csv on HDFS").build())
                .argument(arg("workdir").optional(null).build())
                .argument(arg("model").optional(null).build())
                .argument(arg("league").optional(null).build())
                .argument(arg("season-start").optional("2025-08-01").build())
                .argument(arg("cutoff").optional("2026-01-01").build())
                .argument(arg("season-end").optional("2026-06-30").build())
                .argument(arg("total-sims").optional("10000").build())
                .argument(arg("maps").optional("2").build())
                .argument(arg("seed").optional("42").build())
                .argument(arg("top-places").optional("4").build())
                .argument(arg("relegation-places").optional("auto").build())
                .argument(arg("run-id").optional(null).describedAs("unique five-job run directory id").build())
                .argument(arg("out").optional(null).build())
                .describedAs("Five MR jobs: P5.0 prepare -> P5.1 cutoff table -> P5.3 DC rates -> P5.2 simulation -> P5.4 evaluation")
                .expectedSeconds(180)
                .build());

        specs.put("history-p65", JobSpec.builder("history-p65")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("analysis").describedAs("必填，固定为 p65").build())
                .argument(arg("input").describedAs("three comma-separated CSV paths").build())
                .argument(arg("events").optional(null).describedAs("staged Messi events dir").build())
                .argument(arg("workdir").build())
                .argument(arg("run-id").optional(null).build())
                .argument(arg("player-id").optional("5503").build())
                .describedAs("P6.5: player career curve")
                .expectedSeconds(225)
                .build());

        specs.put("history-p66", JobSpec.builder("history-p66")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("analysis").describedAs("必填，固定为 p66").build())
                .argument(arg("input").describedAs("22-column results_leagues_full.tsv").build())
                .argument(arg("workdir").build())
                .argument(arg("run-id").optional(null).build())
                .describedAs("P6.6: league style evolution")
                .expectedSeconds(36)
                .build());

        specs.put("history-p67", JobSpec.builder("history-p67")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("analysis").describedAs("必填，固定为 p67").build())
                .argument(arg("input").describedAs("international and league results csv").build())
                .argument(arg("workdir").build())
                .argument(arg("run-id").optional(null).build())
                .describedAs("P6.7: scoreline-margin distribution")
                .expectedSeconds(36)
                .build());

        specs.put("history-p68", JobSpec.builder("history-p68")
                .jar(JAR_PARTNER)
                .origin(JobSpec.Origin.PARTNER)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.NAMED)
                .argument(arg("analysis").describedAs("必填，固定为 p68").build())
                .argument(arg("input").describedAs("staged lineups directory").build())
                .argument(arg("workdir").build())
                .argument(arg("run-id").optional(null).build())
                .describedAs("P6.8: player nationality diversity")
                .expectedSeconds(117)
                .build());

        // ---- handoff package: modules 2 and 3, P6.1-P6.4 ----
        for (String command : new String[]{"club-all", "international-all"}) {
            specs.put(command, JobSpec.builder(command)
                    .jar(JAR_HANDOFF_M2).origin(JobSpec.Origin.HANDOFF)
                    .module(JobSpec.Module.MATCH_OUTCOME).argStyle(JobSpec.ArgStyle.POSITIONAL)
                    .argument(pos("raw-input").describedAs("Raw CSV file or dataset directory on HDFS").build())
                    .argument(pos("work-root").describedAs("New HDFS directory for clean, features, model and evaluation").build())
                    .retrains(true).expectedSeconds(1800)
                    .describedAs(command + ": clean, features, train, predict and evaluate a NEW random forest")
                    .build());
        }
        specs.put("club-predict", JobSpec.builder("club-predict")
                .jar(JAR_HANDOFF_M2)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.MATCH_OUTCOME)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("feature-input").describedAs("rf2-split/<dataset>").build())
                .argument(pos("model-path").describedAs("forest directory").build())
                .argument(pos("prediction-output").describedAs("new HDFS output dir").build())
                .describedAs("RF4: random-forest prediction, map-only")
                .expectedSeconds(30)
                .build());

        specs.put("club-evaluate", JobSpec.builder("club-evaluate")
                .jar(JAR_HANDOFF_M2)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.MATCH_OUTCOME)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("prediction-input").build())
                .argument(pos("evaluation-output").build())
                .describedAs("RF5: accuracy / macro-F1 / LogLoss / RPS report")
                .expectedSeconds(25)
                .build());

        specs.put("club-clean", JobSpec.builder("club-clean")
                .jar(JAR_HANDOFF_M2)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.MATCH_OUTCOME)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("raw-input").describedAs("five-league CSV root").build())
                .argument(pos("clean-output").build())
                .describedAs("RF0: clean matches (shot columns HS/AS/HST/AST)")
                .expectedSeconds(60)
                .build());

        specs.put("club-demo", JobSpec.builder("club-demo")
                .jar(JAR_HANDOFF_M2)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.MATCH_OUTCOME)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("raw-input").build())
                .argument(pos("existing-model").build())
                .argument(pos("work-root").build())
                .describedAs("RF0-RF2 + RF4/RF5 without retraining (RF3 skipped)")
                .expectedSeconds(120)
                .build());

        specs.put("score", JobSpec.builder("score")
                .jar(JAR_HANDOFF_M3)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.PLAYER_PREDICTION)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("shots-input").describedAs("p4-0-shots snapshot").build())
                .argument(pos("appearances-input").describedAs("p4-0-appearances snapshot").build())
                .argument(pos("existing-model-dir").describedAs("LR-xG model directory").build())
                .argument(pos("work-root").build())
                .describedAs("P4.3 + P4.4 + P4.6: xG scoring, player aggregation, metrics")
                .expectedSeconds(90)
                .build());

        specs.put("champions", JobSpec.builder("champions")
                .jar(JAR_HANDOFF_M5)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("clean-five-league-matches").describedAs("rf0-clean output").build())
                .argument(pos("output").build())
                .describedAs("P6.1: champion evolution")
                .expectedSeconds(25)
                .build());

        specs.put("home-advantage", JobSpec.builder("home-advantage")
                .jar(JAR_HANDOFF_M5)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("clean-five-league-matches").build())
                .argument(pos("output").build())
                .describedAs("P6.2: home advantage trend")
                .expectedSeconds(25)
                .build());

        specs.put("h2h", JobSpec.builder("h2h")
                .jar(JAR_HANDOFF_M5)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("clean-five-league-matches").build())
                .argument(pos("output").build())
                .describedAs("P6.4: head-to-head matrix")
                .expectedSeconds(25)
                .build());

        specs.put("goal-minutes", JobSpec.builder("goal-minutes")
                .jar(JAR_HANDOFF_M5)
                .origin(JobSpec.Origin.HANDOFF)
                .module(JobSpec.Module.HISTORY)
                .argStyle(JobSpec.ArgStyle.POSITIONAL)
                .argument(pos("statsbomb-raw-root").build())
                .argument(pos("work-root").build())
                .describedAs("P6.3: goal minute distribution")
                .expectedSeconds(300)
                .build());

        SPECS = Collections.unmodifiableMap(specs);
    }

    private JobCatalog() {
    }

    private static JobSpec.Argument.Builder arg(String name) {
        return new JobSpec.Argument.Builder(name);
    }

    private static JobSpec.Argument.Builder pos(String name) {
        return new JobSpec.Argument.Builder(name);
    }

    public static JobSpec get(String id) {
        return SPECS.get(id);
    }

    public static List<JobSpec> all() {
        return new ArrayList<JobSpec>(SPECS.values());
    }

    public static List<JobSpec> byModule(JobSpec.Module module) {
        List<JobSpec> result = new ArrayList<JobSpec>();
        for (JobSpec spec : SPECS.values()) {
            if (spec.module() == module) {
                result.add(spec);
            }
        }
        return result;
    }
}
