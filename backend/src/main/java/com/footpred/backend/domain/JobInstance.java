package com.footpred.backend.domain;

import java.time.LocalDateTime;

/**
 * One row of {@code job_instance}: a single MapReduce command submission.
 *
 * <p>Field names follow {@code AI_HANDOFF.md} section 11, which lists exactly
 * what the backend is expected to receive from the algorithm layer. The
 * additional fields ({@code command}, {@code origin}, {@code argsJson}) are
 * bookkeeping the API needs but the algorithm layer does not have to supply.
 */
public class JobInstance {

    public enum Status {
        PENDING,
        SUBMITTING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        KILLED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == KILLED;
        }
    }

    public Long id;
    /** Archive directory name, e.g. {@code train-20261012-210109}. */
    public String runId;
    public String module;
    /** Catalogue command id, e.g. {@code club-predict}. */
    public String command;
    /** {@code PARTNER} or {@code HANDOFF}. */
    public String origin;
    public String modelVersion;
    public String inputUri;
    public String outputUri;
    /**
     * YARN application of the most recent attempt.
     *
     * <p>Not unique to the job: a {@code train} run submits one application per
     * L-BFGS round, so the full history lives in {@code job_attempt}.
     */
    public String yarnApplicationId;
    public Status status = Status.PENDING;
    /** Full argument snapshot as JSON, so a run can be reproduced exactly. */
    public String argsJson;
    public Integer exitCode;
    public String errorMessage;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public JobInstance() {
    }
}
