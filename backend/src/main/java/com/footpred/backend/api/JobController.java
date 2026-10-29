package com.footpred.backend.api;

import com.footpred.backend.domain.JobAttempt;
import com.footpred.backend.domain.JobInstance;
import com.footpred.backend.job.JobService;
import com.footpred.backend.repository.JobAttemptRepository;
import com.footpred.backend.repository.JobInstanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Job submission and status.
 *
 * <p>Two capabilities sit behind separate flags so a machine can offer
 * different subsets:
 * <ul>
 *   <li>{@code footpred.mysql.enabled} — persist and query job metadata;</li>
 *   <li>{@code footpred.job.enabled} — actually launch {@code hadoop jar}.</li>
 * </ul>
 * Submission requires both. Status endpoints only need the database, so a
 * read-only viewer can still report on jobs submitted elsewhere.
 */
@RestController
@RequestMapping("/api/jobs")
@ConditionalOnProperty(name = "footpred.mysql.enabled", havingValue = "true")
public class JobController {

    private final JobInstanceRepository jobs;
    private final JobAttemptRepository attempts;

    /** Null unless job execution is enabled; guarded at the call site. */
    private final JobService jobService;

    public JobController(JobInstanceRepository jobs, JobAttemptRepository attempts,
                         @Autowired(required = false) JobService jobService) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.jobService = jobService;
    }

    /**
     * Submits a MapReduce command.
     *
     * <p>Returns 202 because the work continues after the response; the client
     * polls {@code GET /api/jobs/{id}} for progress.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody JobService.SubmitRequest request) {
        if (jobService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("job-disabled",
                            "作业提交未启用（footpred.job.enabled=false）"));
        }
        try {
            JobInstance job = jobService.submit(request);
            Map<String, Object> payload = describe(job, true);
            payload.put("poll", "/api/jobs/" + job.id);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(payload);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad-request", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(error("concurrency-limit", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error("launch-failed", e.getMessage()));
        }
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                    @RequestParam(value = "runId", required = false) String runId) {
        List<JobInstance> rows = runId == null || runId.trim().isEmpty()
                ? jobs.findAll(Math.max(1, Math.min(limit, 500)))
                : jobs.findByRunId(runId.trim());
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (JobInstance job : rows) {
            items.add(describe(job, false));
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("count", items.size());
        payload.put("items", items);
        return payload;
    }

    /** One job with its per-round attempt history and live progress. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        JobInstance job = jobs.findById(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> payload = describe(job, true);

        List<JobAttempt> attemptsOfJob = attempts.findByJob(id);
        List<Map<String, Object>> attemptItems = new ArrayList<Map<String, Object>>();
        for (JobAttempt attempt : attemptsOfJob) {
            attemptItems.add(describeAttempt(attempt));
        }
        payload.put("attemptCount", attemptItems.size());
        payload.put("attempts", attemptItems);

        if (jobService != null) {
            payload.putAll(jobService.runtimeState(id));
        }
        return ResponseEntity.ok(payload);
    }

    /** Console tail. Falls back to the stored error message after completion. */
    @GetMapping("/{id}/log")
    public ResponseEntity<Map<String, Object>> log(@PathVariable Long id) {
        JobInstance job = jobs.findById(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("id", id);
        payload.put("runId", job.runId);
        payload.put("status", job.status == null ? null : job.status.name());

        String live = jobService == null ? null : jobService.liveOutput(id);
        if (live != null && !live.isEmpty()) {
            payload.put("source", "live");
            payload.put("log", live);
        } else if (job.errorMessage != null) {
            // Only failures keep their output; successful runs are not stored
            // verbatim, avoiding a table full of Hadoop INFO noise.
            payload.put("source", "stored");
            payload.put("log", job.errorMessage);
        } else {
            payload.put("source", "none");
            payload.put("log", "");
        }
        return ResponseEntity.ok(payload);
    }

    /** Kills a running job. */
    @PostMapping("/{id}/kill")
    public ResponseEntity<Map<String, Object>> kill(@PathVariable Long id) {
        if (jobService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("job-disabled", "作业控制未启用"));
        }
        JobInstance job = jobs.findById(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        boolean killed = jobService.kill(id);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("id", id);
        payload.put("killed", killed);
        payload.put("message", killed ? "已发送终止信号" : "该作业不在运行中");
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        for (JobInstance.Status status : JobInstance.Status.values()) {
            payload.put(status.name(), jobs.countByStatus(status));
        }
        if (jobService != null) {
            payload.put("activeNow", jobService.activeCount());
        }
        return payload;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", code);
        payload.put("message", message);
        return payload;
    }

    private Map<String, Object> describe(JobInstance job, boolean detailed) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", job.id);
        item.put("runId", job.runId);
        item.put("module", job.module);
        item.put("command", job.command);
        item.put("origin", job.origin);
        item.put("status", job.status == null ? null : job.status.name());
        item.put("modelVersion", job.modelVersion);
        item.put("yarnApplicationId", job.yarnApplicationId);
        item.put("startedAt", job.startedAt);
        item.put("finishedAt", job.finishedAt);
        if (detailed) {
            item.put("inputUri", job.inputUri);
            item.put("outputUri", job.outputUri);
            item.put("argsJson", job.argsJson);
            item.put("exitCode", job.exitCode);
            item.put("errorMessage", job.errorMessage);
            item.put("createdAt", job.createdAt);
            item.put("updatedAt", job.updatedAt);
        }
        return item;
    }

    private Map<String, Object> describeAttempt(JobAttempt attempt) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("attemptNo", attempt.attemptNo);
        item.put("yarnApplicationId", attempt.yarnApplicationId);
        item.put("status", attempt.status);
        item.put("progress", attempt.progress);
        item.put("mapProgress", attempt.mapProgress);
        item.put("reduceProgress", attempt.reduceProgress);
        item.put("startedAt", attempt.startedAt);
        item.put("finishedAt", attempt.finishedAt);
        return item;
    }
}
