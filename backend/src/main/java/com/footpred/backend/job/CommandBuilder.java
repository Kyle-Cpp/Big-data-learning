package com.footpred.backend.job;

import com.footpred.backend.catalog.JobSpec;
import com.footpred.backend.catalog.JobCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a validated request into the exact {@code hadoop jar} argument vector.
 *
 * <p>This is where the two packages' incompatible conventions are reconciled.
 * The partner drivers take {@code --key value} pairs and fill in defaults
 * themselves; the handoff drivers take a fixed-length positional list and throw
 * if the count is off by one ({@code requireArgs(args, n, ...)}). Building the
 * vector here — rather than at the call site — keeps that difference in one
 * place and makes it unit-testable without a cluster.
 *
 * <p>Property flags ({@code -D}) must precede the subcommand for both packages,
 * because Hadoop's {@code GenericOptionsParser} consumes them before the driver
 * sees its own arguments.
 */
public final class CommandBuilder {

    private CommandBuilder() {
    }

    /** A fully resolved command line, ready for {@link ProcessBuilder}. */
    public static class Command {
        public final List<String> argv;
        public final Map<String, String> resolved;

        Command(List<String> argv, Map<String, String> resolved) {
            this.argv = Collections.unmodifiableList(argv);
            this.resolved = Collections.unmodifiableMap(resolved);
        }

        @Override
        public String toString() {
            return String.join(" ", argv);
        }
    }

    /**
     * Builds the command for one catalogue entry.
     *
     * @param hadoopBin absolute path to {@code hadoop} (the VM's PATH is not
     *                  inherited by a service)
     * @param properties extra {@code -D} flags, applied before the subcommand
     * @throws IllegalArgumentException when a required argument is missing or an
     *                                  unknown argument is supplied
     */
    public static Command build(JobSpec spec, String hadoopBin, String jarPath,
                                Map<String, String> supplied,
                                Map<String, String> properties) {
        Map<String, String> resolved = resolveArguments(spec, supplied);

        List<String> argv = new ArrayList<String>();
        argv.add(hadoopBin);
        argv.add("jar");
        argv.add(jarPath);
        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                argv.add("-D" + entry.getKey() + "=" + entry.getValue());
            }
        }
        argv.add(subcommand(spec));
        if (spec.argStyle() == JobSpec.ArgStyle.NAMED) {
            appendNamed(argv, spec, resolved);
        } else {
            appendPositional(argv, spec, resolved);
        }
        return new Command(argv, resolved);
    }

    /**
     * Merges supplied values with the declared defaults.
     *
     * <p>Rejects unknown keys: a typo like {@code --modle} would otherwise be
     * silently dropped by the driver and produce a run against the wrong model.
     */
    static Map<String, String> resolveArguments(JobSpec spec, Map<String, String> supplied) {
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        for (JobSpec.Argument argument : spec.arguments()) {
            String value = supplied == null ? null : supplied.get(argument.name());
            if (value == null || value.trim().isEmpty()) {
                value = argument.defaultValue();
            }
            if ((value == null || value.trim().isEmpty()) && argument.required()) {
                throw new IllegalArgumentException(
                        "缺少必填参数 --" + argument.name() + "（命令 " + spec.id() + "）");
            }
            if (value != null && !value.trim().isEmpty()) {
                String trimmed = value.trim();
                if (!argument.choices().isEmpty() && !argument.choices().contains(trimmed)) {
                    throw new IllegalArgumentException("参数 --" + argument.name()
                            + " 只能是 " + argument.choices() + "，收到：" + trimmed);
                }
                resolved.put(argument.name(), trimmed);
            }
        }
        if (supplied != null) {
            for (String key : supplied.keySet()) {
                boolean known = false;
                for (JobSpec.Argument argument : spec.arguments()) {
                    if (argument.name().equals(key)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    throw new IllegalArgumentException(
                            "未知参数 --" + key + "（命令 " + spec.id() + " 不接受该项）");
                }
            }
        }
        return resolved;
    }

    /**
     * Partner style: {@code --key value}, preserving declaration order.
     *
     * <p>Only non-default values are emitted. The drivers apply their own
     * defaults, so passing them again would freeze a value that a future change
     * to the driver's default should have altered.
     */
    private static void appendNamed(List<String> argv, JobSpec spec, Map<String, String> resolved) {
        for (JobSpec.Argument argument : spec.arguments()) {
            String value = resolved.get(argument.name());
            if (value == null) {
                continue;
            }
            if (value.equals(argument.defaultValue())) {
                continue;
            }
            argv.add("--" + argument.name());
            argv.add(value);
        }
    }

    /**
     * Handoff style: positional, in declaration order, no flags.
     *
     * <p>Every declared argument is emitted because {@code requireArgs} checks
     * the exact count; omitting an optional one would fail the whole command.
     */
    private static void appendPositional(List<String> argv, JobSpec spec,
                                         Map<String, String> resolved) {
        for (JobSpec.Argument argument : spec.arguments()) {
            String value = resolved.get(argument.name());
            if (value == null) {
                throw new IllegalArgumentException("位置参数 " + argument.name()
                        + " 不能为空（命令 " + spec.id() + " 要求参数个数完全一致）");
            }
            argv.add(value);
        }
    }

    /**
     * The token the driver actually dispatches on.
     *
     * <p>Most catalogue ids match the subcommand exactly. The history entries are
     * the exception: {@code history-p65} maps to {@code history --analysis p65},
     * where the analysis name is an argument rather than a separate command.
     */
    static String subcommand(JobSpec spec) {
        String id = spec.id();
        if (id.startsWith("history-")) {
            return "history";
        }
        return id;
    }

    /** Convenience: builds from the catalogue by id. */
    public static Command build(String commandId, String hadoopBin, String jarPath,
                                Map<String, String> supplied, Map<String, String> properties) {
        JobSpec spec = JobCatalog.get(commandId);
        if (spec == null) {
            throw new IllegalArgumentException("未知命令：" + commandId);
        }
        return build(spec, hadoopBin, jarPath, supplied, properties);
    }
}
