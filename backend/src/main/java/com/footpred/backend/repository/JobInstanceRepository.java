package com.footpred.backend.repository;

import com.footpred.backend.domain.JobInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence for {@code job_instance}.
 *
 * <p>Plain {@link JdbcTemplate} rather than JPA: the schema is owned by
 * {@code deploy/sql/schema.sql}, the access pattern is a handful of statements,
 * and bulk result import (thousands of rows per job) is an order of magnitude
 * faster through {@code batchUpdate} than through entity persistence.
 */
@Repository
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class JobInstanceRepository {

    private final JdbcTemplate jdbc;

    public JobInstanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<JobInstance> ROW_MAPPER = new RowMapper<JobInstance>() {
        @Override
        public JobInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            JobInstance job = new JobInstance();
            job.id = rs.getLong("id");
            job.runId = rs.getString("run_id");
            job.module = rs.getString("module");
            job.command = rs.getString("command");
            job.origin = rs.getString("origin");
            job.modelVersion = rs.getString("model_version");
            job.inputUri = rs.getString("input_uri");
            job.outputUri = rs.getString("output_uri");
            job.yarnApplicationId = rs.getString("yarn_application_id");
            job.status = parseStatus(rs.getString("status"));
            job.argsJson = rs.getString("args_json");
            int exitCode = rs.getInt("exit_code");
            job.exitCode = rs.wasNull() ? null : exitCode;
            job.errorMessage = rs.getString("error_message");
            job.startedAt = toLocal(rs.getTimestamp("started_at"));
            job.finishedAt = toLocal(rs.getTimestamp("finished_at"));
            job.createdAt = toLocal(rs.getTimestamp("created_at"));
            job.updatedAt = toLocal(rs.getTimestamp("updated_at"));
            return job;
        }
    };

    private static JobInstance.Status parseStatus(String value) {
        if (value == null) {
            return JobInstance.Status.PENDING;
        }
        try {
            return JobInstance.Status.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            // An unknown status from an older row must not break the listing.
            return JobInstance.Status.PENDING;
        }
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    /** Inserts a new job and back-fills the generated id. */
    public Long insert(JobInstance job) {
        String sql = "INSERT INTO job_instance (run_id, module, command, origin, model_version,"
                + " input_uri, output_uri, yarn_application_id, status, args_json, exit_code,"
                + " error_message, started_at, finished_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, job.runId);
            ps.setString(2, job.module);
            ps.setString(3, job.command);
            ps.setString(4, job.origin);
            ps.setString(5, job.modelVersion);
            ps.setString(6, job.inputUri);
            ps.setString(7, job.outputUri);
            ps.setString(8, job.yarnApplicationId);
            ps.setString(9, job.status.name());
            ps.setString(10, job.argsJson);
            if (job.exitCode == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setInt(11, job.exitCode);
            }
            ps.setString(12, job.errorMessage);
            ps.setTimestamp(13, toTimestamp(job.startedAt));
            ps.setTimestamp(14, toTimestamp(job.finishedAt));
            return ps;
        }, keys);
        // MySQL and H2 both return every DEFAULT-populated column (id, created_at,
        // updated_at), so getKey() throws. Ask for the id by name instead.
        Number key = keys.getKeys() == null ? null : (Number) keys.getKeys().get("id");
        job.id = key == null ? null : key.longValue();
        if (job.id == null) {
            // Some drivers return the generated keys keyed by column instead
            // (or not at all). Fall back to the natural key so the caller always
            // gets an id rather than failing later on a null pointer.
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM job_instance WHERE run_id = ? AND command = ? ORDER BY id DESC",
                    Long.class, job.runId, job.command);
            if (!ids.isEmpty()) {
                job.id = ids.get(0);
            }
        }
        return job.id;
    }

    public JobInstance findById(Long id) {
        List<JobInstance> rows = jdbc.query(
                "SELECT * FROM job_instance WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public JobInstance findByRunIdAndCommand(String runId, String command) {
        List<JobInstance> rows = jdbc.query(
                "SELECT * FROM job_instance WHERE run_id = ? AND command = ?",
                ROW_MAPPER, runId, command);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<JobInstance> findAll(int limit) {
        return jdbc.query("SELECT * FROM job_instance ORDER BY created_at DESC, id DESC"
                + " LIMIT ?", ROW_MAPPER, limit);
    }

    public List<JobInstance> findByRunId(String runId) {
        return jdbc.query("SELECT * FROM job_instance WHERE run_id = ? ORDER BY id",
                ROW_MAPPER, runId);
    }

    /** Updates only the lifecycle fields; arguments are immutable once stored. */
    public int updateStatus(Long id, JobInstance.Status status, String applicationId,
                            Integer exitCode, String errorMessage, LocalDateTime finishedAt) {
        return jdbc.update("UPDATE job_instance SET status = ?, yarn_application_id = ?,"
                        + " exit_code = ?, error_message = ?, finished_at = ? WHERE id = ?",
                status.name(), applicationId, exitCode, errorMessage,
                toTimestamp(finishedAt), id);
    }

    public int markStarted(Long id, LocalDateTime startedAt) {
        return jdbc.update("UPDATE job_instance SET status = ?, started_at = ? WHERE id = ?",
                JobInstance.Status.RUNNING.name(), toTimestamp(startedAt), id);
    }

    public int countByStatus(JobInstance.Status status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_instance WHERE status = ?", Integer.class, status.name());
        return count == null ? 0 : count;
    }

    public int deleteByRunId(String runId) {
        return jdbc.update("DELETE FROM job_instance WHERE run_id = ?", runId);
    }
}
