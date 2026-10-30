package com.footpred.backend.domain;

import java.time.LocalDateTime;

/**
 * One row of {@code job_attempt}: one YARN application inside a job.
 *
 * <p>Iterative training is the reason this table exists. {@code train} runs a
 * separate MapReduce job per optimiser round, so a single {@code runId} can own
 * dozens of applications and "what is the job doing" cannot be answered from a
 * single {@code applicationId}.
 */
public class JobAttempt {

    public Long id;
    public Long jobInstanceId;
    /** Round number, 1-based. */
    public int attemptNo;
    public String yarnApplicationId;
    public String status = "RUNNING";
    public int progress;
    public int mapProgress;
    public int reduceProgress;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;

    public JobAttempt() {
    }
}
