package com.footpred.backend.repository;

import com.footpred.backend.domain.JobAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** Persistence for {@code job_attempt} — the per-round detail of iterative jobs. */
@Repository
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class JobAttemptRepository {

    private final JdbcTemplate jdbc;

    public JobAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<JobAttempt> ROW_MAPPER = new RowMapper<JobAttempt>() {
        @Override
        public JobAttempt mapRow(ResultSet rs, int rowNum) throws SQLException {
            JobAttempt attempt = new JobAttempt();
            attempt.id = rs.getLong("id");
            attempt.jobInstanceId = rs.getLong("job_instance_id");
            attempt.attemptNo = rs.getInt("attempt_no");
            attempt.yarnApplicationId = rs.getString("yarn_application_id");
            attempt.status = rs.getString("status");
            attempt.progress = rs.getInt("progress");
            attempt.mapProgress = rs.getInt("map_progress");
            attempt.reduceProgress = rs.getInt("reduce_progress");
            Timestamp started = rs.getTimestamp("started_at");
            attempt.startedAt = started == null ? null : started.toLocalDateTime();
            Timestamp finished = rs.getTimestamp("finished_at");
            attempt.finishedAt = finished == null ? null : finished.toLocalDateTime();
            return attempt;
        }
    };

    public Long insert(JobAttempt attempt) {
        jdbc.update("INSERT INTO job_attempt (job_instance_id, attempt_no, yarn_application_id,"
                        + " status, progress, map_progress, reduce_progress, started_at, finished_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                attempt.jobInstanceId, attempt.attemptNo, attempt.yarnApplicationId,
                attempt.status, attempt.progress, attempt.mapProgress, attempt.reduceProgress,
                attempt.startedAt == null ? null : Timestamp.valueOf(attempt.startedAt),
                attempt.finishedAt == null ? null : Timestamp.valueOf(attempt.finishedAt));
        return attempt.jobInstanceId;
    }

    public List<JobAttempt> findByJob(Long jobInstanceId) {
        return jdbc.query("SELECT * FROM job_attempt WHERE job_instance_id = ? ORDER BY attempt_no",
                ROW_MAPPER, jobInstanceId);
    }

    /**
     * Advances the newest attempt.
     *
     * <p>Called from the progress poller, so it must be cheap: a single indexed
     * update keyed by the parent job and the attempt number.
     */
    /**
     * Binds a YARN application id to an attempt that was created at submit time
     * but had no id yet.
     *
     * <p>Distinct from {@link #updateProgress}: the id is immutable once set, so
     * this is only called for the first application of a job.
     */
    public int attachApplication(Long jobInstanceId, int attemptNo, String applicationId) {
        return jdbc.update("UPDATE job_attempt SET yarn_application_id = ?, status = ?"
                        + " WHERE job_instance_id = ? AND attempt_no = ?",
                applicationId, "RUNNING", jobInstanceId, attemptNo);
    }

    public int updateProgress(Long jobInstanceId, int attemptNo, int progress,
                              int mapProgress, int reduceProgress, String status) {
        return jdbc.update("UPDATE job_attempt SET progress = ?, map_progress = ?,"
                        + " reduce_progress = ?, status = ? WHERE job_instance_id = ? AND attempt_no = ?",
                progress, mapProgress, reduceProgress, status, jobInstanceId, attemptNo);
    }

    public int finishAttempt(Long jobInstanceId, int attemptNo, String status,
                             LocalDateTime finishedAt) {
        return jdbc.update("UPDATE job_attempt SET status = ?, progress = 100, finished_at = ?"
                        + " WHERE job_instance_id = ? AND attempt_no = ?",
                status, finishedAt == null ? null : Timestamp.valueOf(finishedAt),
                jobInstanceId, attemptNo);
    }

    public int nextAttemptNo(Long jobInstanceId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM job_attempt WHERE job_instance_id = ?",
                Integer.class, jobInstanceId);
        return (max == null ? 0 : max) + 1;
    }

    public int countByJob(Long jobInstanceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_attempt WHERE job_instance_id = ?",
                Integer.class, jobInstanceId);
        return count == null ? 0 : count;
    }
}
