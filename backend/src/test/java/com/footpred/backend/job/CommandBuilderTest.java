package com.footpred.backend.job;

import com.footpred.backend.catalog.JobCatalog;
import com.footpred.backend.catalog.JobSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command-line assembly for both argument conventions.
 *
 * <p>Every assertion mirrors what the corresponding driver actually accepts:
 * the handoff drivers check the argument count with exact equality, and the
 * partner driver parses {@code --key value} while ignoring anything it does not
 * recognise. Getting either wrong produces a job that fails immediately with a
 * usage message, so these are worth pinning down precisely.
 */
class CommandBuilderTest {

    private static final String HADOOP = "/opt/module/hadoop-3.3.6/bin/hadoop";

    private Map<String, String> args(String... pairs) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------ handoff style

    @Test
    void clubPredictEmitsThreePositionalArguments() {
        JobSpec spec = JobCatalog.get("club-predict");
        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP,
                "/job/footpred/app/footpred-module2.jar",
                args("feature-input", "/footpred/prepared/rf2-split/test",
                        "model-path", "/footpred/models/club-rf-v4",
                        "prediction-output", "/footpred/runs/abc/rf4"),
                null);

        assertEquals(4, command.argv.size() - 3, "hadoop + jar + jarPath + subcommand + 3 args");
        assertEquals(HADOOP, command.argv.get(0));
        assertEquals("jar", command.argv.get(1));
        assertEquals("club-predict", command.argv.get(3));
        assertEquals("/footpred/prepared/rf2-split/test", command.argv.get(4));
        assertEquals("/footpred/models/club-rf-v4", command.argv.get(5));
        assertEquals("/footpred/runs/abc/rf4", command.argv.get(6));
    }

    @Test
    void missingPositionalArgumentIsRejected() {
        JobSpec spec = JobCatalog.get("club-predict");
        // requireArgs(args, 3, ...) would throw on the cluster; fail early here.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.build(spec, HADOOP, "x.jar",
                        args("feature-input", "/a", "model-path", "/b"), null));
        assertTrue(error.getMessage().contains("prediction-output"));
    }

    @Test
    void propertiesGoBeforeTheSubcommand() {
        JobSpec spec = JobCatalog.get("club-predict");
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("footpred.rf.draw.margin", "0.11");
        properties.put("footpred.rf.draw.margin.auto.tune", "false");

        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "x.jar",
                args("feature-input", "/a", "model-path", "/b", "prediction-output", "/c"),
                properties);

        // GenericOptionsParser consumes -D only when it precedes the subcommand.
        assertEquals("-Dfootpred.rf.draw.margin=0.11", command.argv.get(3));
        assertEquals("-Dfootpred.rf.draw.margin.auto.tune=false", command.argv.get(4));
        assertEquals("club-predict", command.argv.get(5));
    }

    @Test
    void scoreForModuleThreeKeepsFourArguments() {
        JobSpec spec = JobCatalog.get("score");
        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "m3.jar",
                args("shots-input", "/s", "appearances-input", "/a",
                        "existing-model-dir", "/m", "work-root", "/w"), null);

        assertEquals("score", command.argv.get(3));
        assertEquals("/s", command.argv.get(4));
        assertEquals("/a", command.argv.get(5));
        assertEquals("/m", command.argv.get(6));
        assertEquals("/w", command.argv.get(7));
    }

    // ------------------------------------------------------- partner style

    @Test
    void predictEmitsNamedPairsOnly() {
        JobSpec spec = JobCatalog.get("predict");
        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "algo.jar",
                args("model", "/FootPred/module1/work/model.json",
                        "fixtures", "/FootPred/module1/work/fixtures.tsv"),
                null);

        // Required only; --out and --max-goals keep the driver's defaults.
        assertEquals("predict", command.argv.get(3));
        assertEquals("--model", command.argv.get(4));
        assertEquals("/FootPred/module1/work/model.json", command.argv.get(5));
        assertEquals("--fixtures", command.argv.get(6));
        assertEquals("/FootPred/module1/work/fixtures.tsv", command.argv.get(7));
        assertEquals(8, command.argv.size());
    }

    @Test
    void defaultsAreNotEmittedSoDriverDefaultsStayAuthoritative() {
        JobSpec spec = JobCatalog.get("simulate");
        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "algo.jar",
                args("input", "/data/leagues/results_leagues.csv",
                        "workdir", "/FootPred/module4",
                        "model", "/FootPred/module4/leagues_v1/model.json"),
                null);

        String joined = command.toString();
        assertTrue(joined.contains("--input /data/leagues/results_leagues.csv"));
        // season-start/cutoff/total-sims all equal their declared defaults, so
        // they must not be pinned into the command line.
        assertEquals(false, joined.contains("--total-sims"));
        assertEquals(false, joined.contains("--season-start"));
    }

    @Test
    void nonDefaultValueOverridesSilently() {
        JobSpec spec = JobCatalog.get("simulate");
        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "algo.jar",
                args("input", "/data/leagues.csv", "workdir", "/w",
                        "total-sims", "5000", "seed", "7"), null);

        String joined = command.toString();
        assertTrue(joined.contains("--total-sims 5000"));
        assertTrue(joined.contains("--seed 7"));
    }

    // ---------------------------------------------------------- history

    @Test
    void historyCommandsShareTheHistorySubcommand() {
        for (String id : new String[]{"history-p65", "history-p66", "history-p67", "history-p68"}) {
            JobSpec spec = JobCatalog.get(id);
            // The driver dispatches on "history"; the analysis is an argument.
            assertEquals("history", CommandBuilder.subcommand(spec), id);
        }
    }

    @Test
    void historyAnalysisIsRequiredAndMustBeSupplied() {
        JobSpec spec = JobCatalog.get("history-p66");
        // The driver throws when --analysis is absent, so the catalogue marks it
        // required; JobService fills it from the command id on the caller's behalf.
        CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "algo.jar",
                args("analysis", "p66", "input", "/data/leagues_full.tsv",
                        "workdir", "/FootPred/module5"), null);

        assertEquals("history", command.argv.get(3));
        assertTrue(command.toString().contains("--analysis p66"));
    }

    @Test
    void historyWithoutAnalysisIsRejected() {
        JobSpec spec = JobCatalog.get("history-p66");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.build(spec, HADOOP, "algo.jar",
                        args("input", "/data/leagues_full.tsv", "workdir", "/w"), null));
        assertTrue(error.getMessage().contains("analysis"));
    }

    // ------------------------------------------------------- validation

    @Test
    void unknownArgumentIsRejected() {
        JobSpec spec = JobCatalog.get("predict");
        // A typo would be silently ignored by the driver, producing a run with
        // the wrong model; catching it here is the whole point.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.build(spec, HADOOP, "x.jar",
                        args("model", "/m", "fixtures", "/f", "modle", "/typo"), null));
        assertTrue(error.getMessage().contains("modle"));
    }

    @Test
    void unknownCommandIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.build("no-such-command", HADOOP, "x.jar", null, null));
    }

    @Test
    void everyCatalogueEntryBuildsWithItsRequiredArguments() {
        // Guards against adding a command whose required set cannot be satisfied.
        for (JobSpec spec : JobCatalog.all()) {
            if (spec.retrains()) {
                continue; // training commands are not submitted through the API
            }
            Map<String, String> required = new LinkedHashMap<String, String>();
            for (JobSpec.Argument argument : spec.arguments()) {
                if (argument.required()) {
                    required.put(argument.name(), argument.defaultValue() == null
                            ? "/tmp/" + argument.name() : argument.defaultValue());
                }
            }
            CommandBuilder.Command command = CommandBuilder.build(spec, HADOOP, "x.jar",
                    required, null);
            assertTrue(command.argv.contains(CommandBuilder.subcommand(spec)),
                    "subcommand missing for " + spec.id());
        }
    }
}
