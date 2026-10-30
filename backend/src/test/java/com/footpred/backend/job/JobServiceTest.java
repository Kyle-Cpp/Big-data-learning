package com.footpred.backend.job;

import com.footpred.backend.domain.JobAttempt;
import com.footpred.backend.domain.JobInstance;
import com.footpred.backend.repository.JobAttemptRepository;
import com.footpred.backend.repository.JobInstanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle of a submitted job, exercised against a stub {@code hadoop}.
 *
 * <p>The stub prints the same lines real Hadoop prints — including the
 * {@code application_...} identifier on stderr — so argument assembly, output
 * parsing, state transitions and attempt bookkeeping are all covered without a
 * cluster. Those are the parts that break in practice; the MapReduce code itself
 * is not under test here.
 *
 * <p>The stub is generated in the platform's own scripting language (a
 * {@code .cmd} file on Windows, a {@code .sh} elsewhere) because a child process
 * cannot execute a POSIX script on Windows without a shell in between.
 */
@SpringBootTest(properties = {
        "footpred.mysql.enabled=true",
        "footpred.job.enabled=true",
        "footpred.job.max-concurrent=1",
        "footpred.jar.handoff=/tmp/footpred-module2.jar",
        "footpred.jar.handoff.module3=/tmp/footpred-module3.jar",
        "footpred.jar.handoff.module5=/tmp/footpred-module5.jar",
        "footpred.jar.partner=/tmp/footpred-algo.jar",
        "footpred.yarn.rm-url=http://127.0.0.1:1"
})
@ActiveProfiles("mysql-test")
@Sql(scripts = "classpath:schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class JobServiceTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobInstanceRepository jobs;

    @Autowired
    private JobAttemptRepository attempts;

    private static final Path STUB_DIR = Paths.get("target/test-hadoop");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * Points the executor at the stub.
     *
     * <p>Resolved per platform because {@link ProcessBuilder} does no PATHEXT
     * lookup: on Windows the batch file must be named explicitly, while a POSIX
     * script runs directly.
     */
    @DynamicPropertySource
    static void stubPath(DynamicPropertyRegistry registry) {
        registry.add("footpred.hadoop.bin",
                () -> STUB_DIR.resolve(WINDOWS ? "hadoop.cmd" : "hadoop").toString());
    }

    /**
     * Path the executor will run. On Windows a batch file is generated at the
     * same base name so the configured value stays platform independent.
     */
    private static final Path STUB = STUB_DIR.resolve(WINDOWS ? "hadoop.cmd" : "hadoop");

    @BeforeEach
    void installStub() throws IOException {
        Files.createDirectories(STUB_DIR);
        writeStub(0, 0, null);
    }

    @AfterEach
    void removeStub() throws IOException {
        Files.deleteIfExists(STUB);
    }

    /**
     * Writes a stub that mimics a Hadoop client submission.
     *
     * @param exitCode   process exit code
     * @param sleepSecs  delay before exiting, to keep a job "running"
     * @param extraError an extra line to print, or null
     */
    private void writeStub(int exitCode, int sleepSecs, String extraError) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("INFO mapreduce.JobSubmitter: Submitting tokens for job").append('\n');
        body.append("INFO impl.YarnClientImpl: Submitted application ")
                .append("application_1792822091296_0042").append('\n');
        body.append("INFO mapreduce.Job: Running job: job_1792822091296_0042").append('\n');
        if (extraError != null) {
            body.append(extraError).append('\n');
        }
        body.append("map 0% reduce 0%").append('\n');

        if (WINDOWS) {
            StringBuilder cmd = new StringBuilder("@echo off\n");
            // Emit each line via echo; delayed expansion is not needed here.
            for (String line : body.toString().split("\n")) {
                cmd.append("echo ").append(line).append("\r\n");
            }
            if (sleepSecs > 0) {
                // ping is the portable sleep on Windows batch (timeout needs a console).
                cmd.append("ping -n ").append(sleepSecs + 1).append(" 127.0.0.1 >nul\r\n");
            }
            cmd.append("exit /b ").append(exitCode).append("\r\n");
            Files.write(STUB, cmd.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            String script = "#!/bin/sh\n" + body
                    + (sleepSecs > 0 ? "sleep " + sleepSecs + "\n" : "")
                    + "exit " + exitCode + "\n";
            Files.write(STUB, script.getBytes(StandardCharsets.UTF_8));
            STUB.toFile().setExecutable(true);
        }
    }

    private JobService.SubmitRequest request(String command, Map<String, String> arguments) {
        JobService.SubmitRequest request = new JobService.SubmitRequest();
        request.command = command;
        request.runId = "svc-" + System.nanoTime();
        request.arguments = arguments;
        return request;
    }

    private Map<String, String> clubPredictArgs() {
        Map<String, String> args = new LinkedHashMap<String, String>();
        args.put("feature-input", "/footpred/prepared/rf2-split/test");
        args.put("model-path", "/footpred/models/club-rf-v4");
        args.put("prediction-output", "/footpred/runs/r1/module2/rf4");
        return args;
    }

    @Test
    void submitRecordsJobAndReachesSuccess() throws Exception {
        JobInstance job = jobService.submit(request("club-predict", clubPredictArgs()));

        assertNotNull(job.id);
        assertEquals("club-predict", job.command);
        assertEquals("MATCH_OUTCOME", job.module);
        assertEquals("HANDOFF", job.origin);

        JobInstance finished = waitForTerminal(job.id, 25);
        assertEquals(JobInstance.Status.SUCCEEDED, finished.status);
        assertEquals(Integer.valueOf(0), finished.exitCode);
        assertEquals("application_1792822091296_0042", finished.yarnApplicationId);
        assertNotNull(finished.finishedAt);
    }

    @Test
    void applicationIdIsExtractedFromOutput() throws Exception {
        JobInstance job = jobService.submit(request("club-predict", clubPredictArgs()));
        JobInstance finished = waitForTerminal(job.id, 25);

        // Hadoop writes this to stderr; the executor merges the streams so the
        // id is still captured.
        assertEquals("application_1792822091296_0042", finished.yarnApplicationId);
    }

    @Test
    void attemptRowIsCreatedAndFinished() throws Exception {
        JobInstance job = jobService.submit(request("club-predict", clubPredictArgs()));
        waitForTerminal(job.id, 25);

        List<JobAttempt> rows = attempts.findByJob(job.id);
        assertTrue(rows.size() >= 1);
        assertEquals(1, rows.get(0).attemptNo);
    }

    @Test
    void retrainingCommandIsRefused() {
        JobService.SubmitRequest request = request("train", new LinkedHashMap<String, String>());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> jobService.submit(request));

        // The handoff rules forbid triggering a retrain from a live demo.
        assertTrue(error.getMessage().contains("重新训练"));
    }

    @Test
    void unknownCommandIsRefused() {
        JobService.SubmitRequest request = request("rm-rf", new LinkedHashMap<String, String>());
        assertThrows(IllegalArgumentException.class, () -> jobService.submit(request));
    }

    @Test
    void missingRequiredArgumentIsRefused() {
        Map<String, String> incomplete = new LinkedHashMap<String, String>();
        incomplete.put("feature-input", "/a");
        JobService.SubmitRequest request = request("club-predict", incomplete);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> jobService.submit(request));
        assertTrue(error.getMessage().contains("model-path"));
    }

    @Test
    void failedJobStoresExitCodeAndErrorTail() throws Exception {
        writeStub(7, 0, "ERROR: cannot find input /footpred/missing");

        JobInstance job = jobService.submit(request("club-predict", clubPredictArgs()));
        JobInstance finished = waitForTerminal(job.id, 25);

        assertEquals(JobInstance.Status.FAILED, finished.status);
        assertEquals(Integer.valueOf(7), finished.exitCode);
        assertNotNull(finished.errorMessage);
        assertTrue(finished.errorMessage.contains("cannot find input"),
                "stored tail should contain the cause: " + finished.errorMessage);
    }

    @Test
    void concurrencyLimitQueuesSecondJob() throws Exception {
        writeStub(0, 8, null);

        JobInstance first = jobService.submit(request("club-predict", clubPredictArgs()));
        assertNotNull(first.id);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jobService.submit(request("club-predict", clubPredictArgs())));
        // A second concurrent job would contend for the same two cores.
        assertTrue(error.getMessage().contains("并发上限"));

        waitForTerminal(first.id, 40);
    }

    @Test
    void differentModulesUseDifferentJars() throws Exception {
        // score belongs to module 3 and champions to module 5; using the module-2
        // jar would dispatch to the wrong main class and fail on the subcommand.
        JobInstance score = jobService.submit(request("score", scoreArgs()));
        assertNotNull(score.id);
        waitForTerminal(score.id, 25);

        JobInstance champions = jobService.submit(request("champions", championsArgs()));
        assertNotNull(champions.id);
        waitForTerminal(champions.id, 25);
    }

    private Map<String, String> scoreArgs() {
        Map<String, String> args = new LinkedHashMap<String, String>();
        args.put("shots-input", "/footpred/prepared/module3-v1/p4-0-shots");
        args.put("appearances-input", "/footpred/prepared/module3-v1/p4-0-appearances");
        args.put("existing-model-dir", "/footpred/models/module3/player-xg-v1");
        args.put("work-root", "/footpred/runs/r1/module3");
        return args;
    }

    private Map<String, String> championsArgs() {
        Map<String, String> args = new LinkedHashMap<String, String>();
        args.put("clean-five-league-matches", "/footpred/prepared/module2-v3/rf0-clean");
        args.put("output", "/footpred/runs/r1/module5/p6-1-champions");
        return args;
    }

    /**
     * Waits for a terminal state in short increments.
     *
     * <p>The poller fires every five seconds, so polling faster than that would
     * not observe anything new; the budget still lets a hung stub fail the test
     * instead of stalling the build.
     */
    private JobInstance waitForTerminal(Long jobId, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JobInstance job = jobs.findById(jobId);
            if (job != null && job.status != null && job.status.isTerminal()) {
                return job;
            }
            Thread.sleep(250);
        }
        JobInstance job = jobs.findById(jobId);
        throw new AssertionError("job " + jobId + " did not finish within " + seconds
                + "s, last status=" + (job == null ? "null" : job.status));
    }
}
