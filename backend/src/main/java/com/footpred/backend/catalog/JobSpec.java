package com.footpred.backend.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative description of one MapReduce command.
 *
 * <p>The two packages have incompatible argument conventions, so a command is
 * described here rather than assembled ad hoc by callers:
 * <ul>
 *   <li>{@link ArgStyle#NAMED} — partner package, {@code --key value} pairs
 *       with optional defaults, validated by {@code FootPredDriver};</li>
 *   <li>{@link ArgStyle#POSITIONAL} — handoff package, a fixed-length list
 *       validated by {@code requireArgs(args, n, ...)}, which throws when the
 *       count is off by even one.</li>
 * </ul>
 */
public class JobSpec {

    public enum ArgStyle {
        /** {@code --model X --fixtures Y} (partner package). */
        NAMED,
        /** {@code <input> <model> <output>} (handoff package). */
        POSITIONAL
    }

    /** Package that owns the command; decides JAR and HDFS root. */
    public enum Origin {
        /** Modules 1 and 4, plus P6.5-P6.8. */
        PARTNER,
        /** Modules 2 and 3, plus P6.1-P6.4. */
        HANDOFF
    }

    /** Logical module, used for grouping in the API. */
    public enum Module {
        SCORE_PREDICTION,
        MATCH_OUTCOME,
        PLAYER_PREDICTION,
        SEASON_SIMULATION,
        HISTORY
    }

    private final String id;
    private final String jarKey;
    private final Origin origin;
    private final Module module;
    private final ArgStyle argStyle;
    private final List<Argument> arguments;
    private final String description;
    private final boolean retrains;
    private final long expectedSeconds;

    private JobSpec(Builder builder) {
        this.id = builder.id;
        this.jarKey = builder.jarKey;
        this.origin = builder.origin;
        this.module = builder.module;
        this.argStyle = builder.argStyle;
        this.arguments = Collections.unmodifiableList(new ArrayList<Argument>(builder.arguments));
        this.description = builder.description;
        this.retrains = builder.retrains;
        this.expectedSeconds = builder.expectedSeconds;
    }

    public String id() {
        return id;
    }

    public String jarKey() {
        return jarKey;
    }

    public Origin origin() {
        return origin;
    }

    public Module module() {
        return module;
    }

    public ArgStyle argStyle() {
        return argStyle;
    }

    public List<Argument> arguments() {
        return arguments;
    }

    public String description() {
        return description;
    }

    /** True when the command trains a model and must not run in a live demo. */
    public boolean retrains() {
        return retrains;
    }

    /** Rough wall-clock expectation on the 2-core reference VM. */
    public long expectedSeconds() {
        return expectedSeconds;
    }

    public String jarSystemProperty() {
        return origin == Origin.HANDOFF ? "footpred.jar.handoff" : "footpred.jar.partner";
    }

    public String hdfsRootSystemProperty() {
        return origin == Origin.HANDOFF ? "footpred.hdfs.root" : "footpred.hdfs.root.partner";
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** One command-line argument. */
    public static class Argument {
        private final String name;
        private final boolean required;
        private final String defaultValue;
        private final String description;
        /** Bounded choice set; empty means free form. */
        private final List<String> choices;

        private Argument(Builder builder) {
            this.name = builder.name;
            this.required = builder.required;
            this.defaultValue = builder.defaultValue;
            this.description = builder.description;
            this.choices = builder.choices == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(builder.choices));
        }

        public String name() {
            return name;
        }

        public boolean required() {
            return required;
        }

        public String defaultValue() {
            return defaultValue;
        }

        public String description() {
            return description;
        }

        public List<String> choices() {
            return choices;
        }

        public static class Builder {
            private final String name;
            private boolean required = true;
            private String defaultValue;
            private String description = "";
            private List<String> choices;

            public Builder(String name) {
                this.name = name;
            }

            public Builder optional(String value) {
                this.required = false;
                this.defaultValue = value;
                return this;
            }

            public Builder describedAs(String text) {
                this.description = text;
                return this;
            }

            public Builder choices(String... values) {
                this.choices = new ArrayList<String>();
                Collections.addAll(this.choices, values);
                return this;
            }

            public Argument build() {
                return new Argument(this);
            }
        }
    }

    public static class Builder {
        private final String id;
        private String jarKey = "";
        private Origin origin = Origin.HANDOFF;
        private Module module = Module.HISTORY;
        private ArgStyle argStyle = ArgStyle.POSITIONAL;
        private final List<Argument> arguments = new ArrayList<Argument>();
        private String description = "";
        private boolean retrains;
        private long expectedSeconds = 60L;

        private Builder(String id) {
            this.id = id;
        }

        public Builder jar(String key) {
            this.jarKey = key;
            return this;
        }

        public Builder origin(Origin value) {
            this.origin = value;
            return this;
        }

        public Builder module(Module value) {
            this.module = value;
            return this;
        }

        public Builder argStyle(ArgStyle value) {
            this.argStyle = value;
            return this;
        }

        public Builder argument(Argument value) {
            this.arguments.add(value);
            return this;
        }

        public Builder describedAs(String text) {
            this.description = text;
            return this;
        }

        public Builder retrains(boolean value) {
            this.retrains = value;
            return this;
        }

        public Builder expectedSeconds(long value) {
            this.expectedSeconds = value;
            return this;
        }

        public JobSpec build() {
            return new JobSpec(this);
        }
    }
}
