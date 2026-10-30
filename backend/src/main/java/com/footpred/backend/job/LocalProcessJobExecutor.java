package com.footpred.backend.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code hadoop jar} as a local child process and streams its output.
 *
 * <p>Two details are load bearing:
 * <ul>
 *   <li>stderr must be merged into stdout. Hadoop writes the application id to
 *       stderr via log4j, and the {@code [footpred]} trajectory of the DC
 *       trainer also goes to stderr; reading only stdout would lose both.</li>
 *   <li>output is drained continuously into a bounded ring buffer. A child that
 *       writes more than the pipe buffer while nobody reads it blocks forever,
 *       which would look like a hung job.</li>
 * </ul>
 */
public class LocalProcessJobExecutor implements JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessJobExecutor.class);

    /**
     * Matches the application id Hadoop prints when submitting, e.g.
     * {@code Submitted application application_1792822091296_0042}.
     */
    private static final Pattern APPLICATION_ID =
            Pattern.compile("application_\\d+_\\d+");

    /** How many output lines to keep per job. */
    private static final int RETAINED_LINES = 400;

    @Override
    public Handle start(CommandBuilder.Command command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command.argv);
        builder.redirectErrorStream(true);
        log.info("starting job: {}", command);

        Process process = builder.start();
        return new ProcessHandle(process, command);
    }

    private static final class ProcessHandle implements Handle {
        private final Process process;
        private final CommandBuilder.Command command;
        private final Deque<String> output = new ArrayDeque<String>();
        private final Thread pump;
        private volatile String applicationId;

        ProcessHandle(Process process, CommandBuilder.Command command) {
            this.process = process;
            this.command = command;
            this.pump = new Thread(this::drain, "job-output");
            this.pump.setDaemon(true);
            this.pump.start();
        }

        private void drain() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.addLast(line);
                        while (output.size() > RETAINED_LINES) {
                            output.removeFirst();
                        }
                    }
                    if (applicationId == null) {
                        Matcher matcher = APPLICATION_ID.matcher(line);
                        if (matcher.find()) {
                            applicationId = matcher.group();
                            log.info("{} -> {}", command.resolved.get("workdir"), applicationId);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("output stream closed: {}", e.getMessage());
            }
        }

        @Override
        public String applicationId() {
            return applicationId;
        }

        @Override
        public boolean isRunning() {
            return process.isAlive();
        }

        @Override
        public Integer exitCode() {
            return process.isAlive() ? null : process.exitValue();
        }

        @Override
        public void kill() {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        @Override
        public String recentOutput() {
            StringBuilder text = new StringBuilder();
            synchronized (output) {
                for (String line : output) {
                    text.append(line).append('\n');
                }
            }
            return text.toString();
        }

        @Override
        public boolean waitFor(long seconds) {
            try {
                return process.waitFor(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
