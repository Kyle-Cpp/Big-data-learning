package com.footpred.backend.service;

import com.footpred.backend.catalog.JobSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a command writes its results.
 *
 * <p>Derived from the two drivers, which disagree on layout:
 * <ul>
 *   <li>handoff runs live under {@code <HDFS>/handoff/runs/<runId>/moduleN/...}
 *       and every sub-output name is fixed by the scripts;</li>
 *   <li>partner runs live under {@code <workdir>/runs/<runId>/...} with the
 *       snapshot {@code <workdir>/model.json} refreshed after a train.</li>
 * </ul>
 * Because the location is a pure function of the command and its arguments,
 * the API can answer "where is the result of this job" without storing it.
 */
public final class OutputLocator {

    private final String handoffRoot;
    private final String partnerRoot;

    public OutputLocator(String handoffRoot, String partnerRoot) {
        this.handoffRoot = trimTrailingSlash(handoffRoot);
        this.partnerRoot = trimTrailingSlash(partnerRoot);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public String handoffRoot() {
        return handoffRoot;
    }

    public String partnerRoot() {
        return partnerRoot;
    }

    /**
     * Named result locations for one completed run.
     *
     * <p>Keys are stable API identifiers ({@code "predictions-test"}), values are
     * absolute locations the {@link ResultReader} understands.
     */
    public Map<String, String> locate(JobSpec spec, String runId, Map<String, String> arguments) {
        Map<String, String> outputs = new LinkedHashMap<String, String>();
        String command = spec.id();
        if ("club-predict".equals(command)) {
            // Handoff scripts write rf4-predictions-<split>; the split is the last
            // path segment of the feature input.
            String split = lastSegment(argument(arguments, "feature-input"));
            outputs.put("predictions-" + split,
                    join(handoffRoot, "handoff/runs", runId, "module2/rf4-predictions-" + split));
        } else if ("club-evaluate".equals(command)) {
            String split = inferSplitFromPredictions(argument(arguments, "prediction-input"));
            outputs.put("evaluation-" + split,
                    join(handoffRoot, "handoff/runs", runId, "module2/rf5-evaluation-" + split));
        } else if ("club-clean".equals(command)) {
            outputs.put("clean", argument(arguments, "clean-output"));
        } else if ("club-demo".equals(command)) {
            outputs.put("predictions-test",
                    join(handoffRoot, "handoff/runs", runId, "module2/rf4-predictions-test"));
            outputs.put("evaluation-test",
                    join(handoffRoot, "handoff/runs", runId, "module2/rf5-evaluation-test"));
        } else if ("score".equals(command)) {
            outputs.put("shots-scored",
                    join(handoffRoot, "handoff/runs", runId, "module3/p4-3-shot-xg"));
            outputs.put("players",
                    join(handoffRoot, "handoff/runs", runId, "module3/p4-4-player-predictions"));
            outputs.put("evaluation",
                    join(handoffRoot, "handoff/runs", runId, "module3/p4-6-evaluation"));
        } else if ("champions".equals(command)) {
            outputs.put("champions", argumentOrDefault(arguments, "output",
                    join(handoffRoot, "handoff/runs", runId, "module5/p6-1-champions")));
        } else if ("home-advantage".equals(command)) {
            outputs.put("home-advantage", argumentOrDefault(arguments, "output",
                    join(handoffRoot, "handoff/runs", runId, "module5/p6-2-home-advantage")));
        } else if ("h2h".equals(command)) {
            outputs.put("h2h", argumentOrDefault(arguments, "output",
                    join(handoffRoot, "handoff/runs", runId, "module5/p6-4-h2h")));
        } else if ("goal-minutes".equals(command)) {
            outputs.put("goal-minutes",
                    join(handoffRoot, "handoff/runs", runId, "module5/p6-3-goal-minutes"));
        } else if ("predict".equals(command)) {
            String out = argument(arguments, "out");
            outputs.put("predictions", out != null ? out : join(partnerRoot, runId, "predictions"));
        } else if ("simulate".equals(command)) {
            String out = argument(arguments, "out");
            String simulation = out != null ? out : join(partnerRoot, runId, "simulation");
            outputs.put("simulation", simulation);
            // The driver writes the real table next to the simulation directory.
            outputs.put("real-final-table",
                    join(parentOf(simulation), "real_final_table.tsv"));
        } else if ("evaluate".equals(command)) {
            outputs.put("evaluation", join(partnerRoot, runId, "evaluation"));
        } else if ("baseline".equals(command)) {
            outputs.put("baseline-stats", join(partnerRoot, runId, "baseline_stats"));
            outputs.put("baseline-eval", join(partnerRoot, runId, "baseline_eval"));
        } else if ("train".equals(command)) {
            outputs.put("model", join(partnerRoot, runId, "model.json"));
            outputs.put("train-log", join(partnerRoot, runId, "train_log.txt"));
            outputs.put("team-weights", join(partnerRoot, runId, "team_weights.tsv"));
        } else if (command.startsWith("history-")) {
            String analysis = command.substring("history-".length());
            outputs.put(analysis, join(partnerRoot, runId, analysis));
        } else if ("prepare".equals(command)) {
            String workdir = argument(arguments, "workdir");
            outputs.put("registry", join(workdir, "registry.tsv"));
            outputs.put("training-data", join(workdir, "training_data"));
        }
        return outputs;
    }

    private static String inferSplitFromPredictions(String predictionsPath) {
        // .../rf4-predictions-test -> test
        String last = lastSegment(predictionsPath);
        int marker = last.indexOf("rf4-predictions-");
        if (marker >= 0) {
            String split = last.substring(marker + "rf4-predictions-".length());
            if (!split.isEmpty()) {
                return split;
            }
        }
        return "test";
    }

    private static String argument(Map<String, String> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        String value = arguments.get(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String argumentOrDefault(Map<String, String> arguments, String key,
                                            String fallback) {
        String value = argument(arguments, key);
        return value != null ? value : fallback;
    }

    private static String lastSegment(String path) {
        if (path == null) {
            return "";
        }
        String value = path.trim();
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String parentOf(String path) {
        if (path == null) {
            return "";
        }
        String value = path;
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        return slash > 0 ? value.substring(0, slash) : "";
    }

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '/') {
                builder.append('/');
            }
            String value = part;
            while (value.startsWith("/") && builder.length() > 0) {
                value = value.substring(1);
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
