package com.footpred.backend.job;

import java.io.IOException;

/**
 * Runs one {@code hadoop jar} invocation and reports its lifecycle.
 *
 * <p>Abstracted so the same orchestration can run against the VM's local shell
 * (the chosen deployment) or, later, over SSH from a Windows host without
 * changing the service above it.
 */
public interface JobExecutor {

    /**
     * Starts the command and returns immediately.
     *
     * <p>Both packages run for tens of seconds to several minutes; blocking the
     * HTTP request would time out long before the job finished, and the report
     * explicitly requires the front end to poll rather than wait.
     */
    Handle start(CommandBuilder.Command command) throws IOException;

    /** One running invocation. */
    interface Handle {
        /**
         * The YARN application id, or null while the client has not printed it.
         *
         * <p>Extracted from the client output rather than assumed, because
         * {@code train} creates a new application on every optimiser round and
         * only the running process knows the current one.
         */
        String applicationId();

        /** True while the client process is alive. */
        boolean isRunning();

        /** Client exit code, or null while still running. */
        Integer exitCode();

        /** Kills the client process; YARN cleans up the application itself. */
        void kill();

        /** Tail of the client's combined stdout/stderr, for the log endpoint. */
        String recentOutput();

        /** Blocks until the process ends, with a bounded wait in seconds. */
        boolean waitFor(long seconds);
    }
}
