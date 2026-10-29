package com.footpred.backend.repository;

import com.footpred.backend.domain.JobAttempt;
import com.footpred.backend.domain.JobInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metadata persistence, exercised against the same DDL the VM will run.
 *
 * <p>H2 in MySQL mode is used so the schema is validated on every build without
 * requiring a database. The one MySQL-specific detail H2 cannot check is
 * collation, which only affects case-insensitive comparisons and is not relied
 * on anywhere.
 */
@SpringBootTest(properties = "footpred.mysql.enabled=true")
@ActiveProfiles("mysql-test")
@Sql(scripts = "classpath:schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class JobMetadataRepositoryTest {

    @Autowired
    private JobInstanceRepository jobs;

    @Autowired
    private JobAttemptRepository attempts;

    private JobInstance newJob(String runId, String command) {
        JobInstance job = new JobInstance();
        job.runId = runId;
        job.command = command;
        job.module = "MATCH_OUTCOME";
        job.origin = "HANDOFF";
        job.modelVersion = "3.0";
        job.inputUri = "/footpred/handoff/prepared/module2-v3/rf2-split/test";
        job.outputUri = "/footpred/handoff/runs/" + runId + "/module2/rf4-predictions-test";
        job.status = JobInstance.Status.PENDING;
        return job;
    }

    @Test
    void insertAssignsGeneratedId() {
        JobInstance job = newJob("20261025-100000", "club-predict");
        Long id = jobs.insert(job);

        assertNotNull(id);
        assertNotNull(job.id);
        assertEquals(id, job.id);
    }

    @Test
    void insertedJobRoundTripsAllFields() {
        JobInstance job = newJob("20261025-100001", "club-evaluate");
        job.argsJson = "{\"prediction-input\":\"/a\",\"evaluation-output\":\"/b\"}";
        jobs.insert(job);

        JobInstance loaded = jobs.findByRunIdAndCommand("20261025-100001", "club-evaluate");
        assertNotNull(loaded);
        assertEquals("MATCH_OUTCOME", loaded.module);
        assertEquals("HANDOFF", loaded.origin);
        assertEquals("3.0", loaded.modelVersion);
        assertEquals(JobInstance.Status.PENDING, loaded.status);
        assertEquals(job.argsJson, loaded.argsJson);
        assertNull(loaded.exitCode);
        assertNull(loaded.finishedAt);
    }

    @Test
    void statusLifecycleIsPersisted() {
        JobInstance job = newJob("20261025-100002", "club-predict");
        jobs.insert(job);

        LocalDateTime started = LocalDateTime.of(2026, 10, 25, 10, 0, 5);
        jobs.markStarted(job.id, started);
        JobInstance running = jobs.findById(job.id);
        assertEquals(JobInstance.Status.RUNNING, running.status);
        assertNotNull(running.startedAt);

        LocalDateTime finished = LocalDateTime.of(2026, 10, 25, 10, 0, 35);
        jobs.updateStatus(job.id, JobInstance.Status.SUCCEEDED,
                "application_1792822091296_0042", 0, null, finished);
        JobInstance done = jobs.findById(job.id);
        assertEquals(JobInstance.Status.SUCCEEDED, done.status);
        assertEquals("application_1792822091296_0042", done.yarnApplicationId);
        assertEquals(Integer.valueOf(0), done.exitCode);
        assertNotNull(done.finishedAt);
        assertTrue(done.status.isTerminal());
    }

    @Test
    void failureKeepsExitCodeAndMessage() {
        JobInstance job = newJob("20261025-100003", "club-predict");
        jobs.insert(job);
        jobs.updateStatus(job.id, JobInstance.Status.FAILED, null, 10,
                "Refusing to overwrite existing HDFS output", LocalDateTime.now());

        JobInstance failed = jobs.findById(job.id);
        assertEquals(JobInstance.Status.FAILED, failed.status);
        assertEquals(Integer.valueOf(10), failed.exitCode);
        assertTrue(failed.errorMessage.contains("Refusing to overwrite"));
    }

    /**
     * The handoff package refuses to reuse a run directory, so the unique key is
     * (run_id, command) rather than run_id alone: the same run legitimately holds
     * several commands.
     */
    @Test
    void sameRunHoldsSeveralCommands() {
        jobs.insert(newJob("20261025-100004", "club-predict"));
        jobs.insert(newJob("20261025-100004", "club-evaluate"));
        jobs.insert(newJob("20261025-100004", "champions"));

        List<JobInstance> rows = jobs.findByRunId("20261025-100004");
        assertEquals(3, rows.size());
    }

    /**
     * Iterative training is why attempts exist: one application per optimiser
     * round under a single job.
     */
    @Test
    void trainJobTracksEveryOptimiserRound() {
        JobInstance job = newJob("20261025-100005", "train");
        job.command = "train";
        job.module = "SCORE_PREDICTION";
        job.origin = "PARTNER";
        jobs.insert(job);

        assertEquals(1, attempts.nextAttemptNo(job.id));
        for (int round = 1; round <= 5; round++) {
            JobAttempt attempt = new JobAttempt();
            attempt.jobInstanceId = job.id;
            attempt.attemptNo = round;
            attempt.yarnApplicationId = "application_1792822091296_01" + String.format("%02d", round);
            attempt.status = "RUNNING";
            attempt.progress = 10;
            attempt.startedAt = LocalDateTime.now();
            attempts.insert(attempt);
        }
        assertEquals(6, attempts.nextAttemptNo(job.id));
        assertEquals(5, attempts.countByJob(job.id));

        List<JobAttempt> all = attempts.findByJob(job.id);
        assertEquals(5, all.size());
        assertEquals(1, all.get(0).attemptNo);
        assertEquals(5, all.get(4).attemptNo);

        // A later round advances independently of the others.
        attempts.updateProgress(job.id, 3, 65, 80, 55, "RUNNING");
        List<JobAttempt> updated = attempts.findByJob(job.id);
        assertEquals(65, updated.get(2).progress);
        assertEquals(80, updated.get(2).mapProgress);
        assertEquals(10, updated.get(0).progress);

        attempts.finishAttempt(job.id, 5, "SUCCEEDED", LocalDateTime.now());
        JobAttempt last = attempts.findByJob(job.id).get(4);
        assertEquals("SUCCEEDED", last.status);
        assertEquals(100, last.progress);
        assertNotNull(last.finishedAt);
    }

    @Test
    void summaryCountsEveryStatus() {
        // The shared in-memory database keeps rows from earlier tests, so the
        // assertion is on the delta rather than an absolute count.
        int pendingBefore = jobs.countByStatus(JobInstance.Status.PENDING);
        int runningBefore = jobs.countByStatus(JobInstance.Status.RUNNING);
        int succeededBefore = jobs.countByStatus(JobInstance.Status.SUCCEEDED);

        jobs.insert(newJob("20261025-100006", "club-predict"));
        JobInstance running = newJob("20261025-100007", "club-predict");
        jobs.insert(running);
        jobs.markStarted(running.id, LocalDateTime.now());

        assertEquals(pendingBefore + 1, jobs.countByStatus(JobInstance.Status.PENDING));
        assertEquals(runningBefore + 1, jobs.countByStatus(JobInstance.Status.RUNNING));
        assertEquals(succeededBefore, jobs.countByStatus(JobInstance.Status.SUCCEEDED));
    }

    @Test
    void listIsNewestFirstAndRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            jobs.insert(newJob("20261025-10100" + i, "club-predict"));
        }
        List<JobInstance> limited = jobs.findAll(3);
        assertEquals(3, limited.size());
        assertTrue(limited.get(0).id > limited.get(1).id);
    }

    @Test
    void deletingRunCascadesToAttempts() {
        JobInstance job = newJob("20261025-100008", "train");
        jobs.insert(job);
        JobAttempt attempt = new JobAttempt();
        attempt.jobInstanceId = job.id;
        attempt.attemptNo = 1;
        attempt.status = "RUNNING";
        attempts.insert(attempt);
        assertEquals(1, attempts.countByJob(job.id));

        jobs.deleteByRunId("20261025-100008");
        assertEquals(0, attempts.countByJob(job.id));
    }
}
