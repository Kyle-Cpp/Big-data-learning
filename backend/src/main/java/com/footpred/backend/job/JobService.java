package com.footpred.backend.job;

import com.footpred.backend.catalog.JobCatalog;
import com.footpred.backend.catalog.JobSpec;
import com.footpred.backend.domain.JobAttempt;
import com.footpred.backend.domain.JobInstance;
import com.footpred.backend.repository.JobAttemptRepository;
import com.footpred.backend.repository.JobInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Submits MapReduce commands and tracks them to completion.
 *
 * <p>The concurrency limit defaults to one because the reference environment is a
 * single 2-core VM: a second YARN application would compete for the same
 * containers and make both slower, while also corrupting any timing comparison.
 * Queueing is therefore the correct behaviour, not a limitation.
 *
 * <p>A background poller refreshes YARN state every few seconds and records each
 * new application id as a separate {@code job_attempt}. That is what makes
 * {@code train} — one application per optimiser round — observable instead of
 * appearing as a single opaque job.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = {"footpred.job.enabled", "footpred.mysql.enabled"}, havingValue = "true")
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);


    private final JobExecutor executor;
    private final YarnMonitor yarn;
    private final JobInstanceRepository jobs;
    private final JobAttemptRepository attempts;
    private final String hadoopBin;
    private final String handoffJar;
    private final String module3Jar;
    private final String module5Jar;
    private final String partnerJar;
    @Value("${footpred.jar.partner.module4:}")
    private String module4Jar;
    private final int maxConcurrent;
    @Value("${footpred.job.training.enabled:false}")
    private boolean trainingEnabled;

    /** Live handles, keyed by job id. Absent for jobs after a restart. */
    private final Map<Long, JobExecutor.Handle> running =
            new ConcurrentHashMap<Long, JobExecutor.Handle>();
    /** Attempt number currently being watched, keyed by job id. */
    private final Map<Long, Integer> currentAttempt = new ConcurrentHashMap<Long, Integer>();
    /** Most recent YARN application per job, to detect a new optimiser round. */
    private final Map<Long, String> lastApplicationId = new ConcurrentHashMap<Long, String>();
    /** Marks jobs whose first application id has already been attached. */
    private final Map<Long, Boolean> lastApplicationIdFilled =
            new ConcurrentHashMap<Long, Boolean>();

    private final ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "job-poller");
                thread.setDaemon(true);
                return thread;
            });

    public JobService(
                      JobExecutor executor,
                      YarnMonitor yarn,
                      JobInstanceRepository jobs,
                      JobAttemptRepository attempts,
                      @Value("${footpred.hadoop.bin:hadoop}") String hadoopBin,
                      @Value("${footpred.jar.handoff:}") String handoffJar,
                      @Value("${footpred.jar.handoff.module3:}") String module3Jar,
                      @Value("${footpred.jar.handoff.module5:}") String module5Jar,
                      @Value("${footpred.jar.partner:}") String partnerJar,
                      @Value("${footpred.job.max-concurrent:1}") int maxConcurrent) {

        this.executor = executor;
        this.yarn = yarn;
        this.jobs = jobs;
        this.attempts = attempts;
        this.hadoopBin = hadoopBin;
        this.handoffJar = handoffJar;
        this.module3Jar = module3Jar;
        this.module5Jar = module5Jar;
        this.partnerJar = partnerJar;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        poller.scheduleWithFixedDelay(this::pollAll, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Picks the JAR for a command.
     *
     * <p>The handoff package deliberately ships one JAR per module with a
     * separate main class each, so a single "handoff jar" setting would run
     * {@code score} against the module-2 entry point and fail with an unknown
     * subcommand.
     */
    private String jarFor(JobSpec spec) {
        if (spec.origin() == JobSpec.Origin.PARTNER) {
            if ("simulate".equals(spec.id()) && module4Jar != null && !module4Jar.trim().isEmpty()) {
                return module4Jar;
            }
            return partnerJar;
        }
        String id = spec.id();
        if ("score".equals(id)) {
            return module3Jar != null && !module3Jar.trim().isEmpty() ? module3Jar : handoffJar;
        }
        if ("champions".equals(id) || "home-advantage".equals(id) || "h2h".equals(id)
                || "goal-minutes".equals(id)) {
            return module5Jar != null && !module5Jar.trim().isEmpty() ? module5Jar : handoffJar;
        }
        return handoffJar;
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        for (JobExecutor.Handle handle : running.values()) {
            handle.kill();
        }
    }

    /** Request body accepted by the submit endpoint. */
    public static class SubmitRequest {
        public String command;
        public String runId;
        public Map<String, String> arguments = new LinkedHashMap<String, String>();
    }

    /**
     * Submits a command and returns the recorded instance.
     *
     * @throws IOException              when the process cannot be started
     * @throws IllegalArgumentException for an unknown command or bad arguments
     * @throws IllegalStateException    when the concurrency limit is reached
     */
    public JobInstance submit(SubmitRequest request) throws IOException {
        JobSpec spec = JobCatalog.get(request.command);
        if (spec == null) {
            throw new IllegalArgumentException("未知命令：" + request.command);
        }
        if (spec.retrains() && !trainingEnabled) {
            // The handoff package forbids retraining during a live demo; the API
            // enforces the same rule so nobody triggers it by accident.
            throw new IllegalArgumentException(
                    "命令 " + spec.id() + " 会重新训练，不在演示流程中提交。"
                            + "请配置 footpred.job.training.enabled=true 后再提交。");
        }
        long active = running.values().stream().filter(JobExecutor.Handle::isRunning).count();
        if (active >= maxConcurrent) {
            throw new IllegalStateException(
                    "已达并发上限 " + maxConcurrent + "（单节点 2 核机器不建议并行），请等待当前作业完成");
        }

        String jarPath = jarFor(spec);
        if (jarPath == null || jarPath.trim().isEmpty()) {
            throw new IllegalStateException("未配置 " + spec.id() + " 对应的 JAR 路径");
        }
        String runId = request.runId == null || request.runId.trim().isEmpty()
                ? generateRunId(spec)
                : request.runId.trim();

        Map<String, String> properties = new LinkedHashMap<String, String>();
        if (spec.origin() == JobSpec.Origin.HANDOFF && !spec.retrains()) {
            // Both margins must be explicit: the packaged default is 0.10/auto-tune,
            // while the delivered models assume fixed 0.11 and 0.15.
            String margin = "club-predict".equals(spec.id()) || "club-evaluate".equals(spec.id())
                    || "club-demo".equals(spec.id()) ? "0.11" : "0.15";
            properties.put("footpred.rf.draw.margin", margin);
            properties.put("footpred.rf.draw.margin.auto.tune", "false");
        }

        // The history driver requires --analysis explicitly (it throws when the
        // option is absent), and the value is fixed by which catalogue entry was
        // chosen, so callers should not have to repeat it.
        Map<String, String> supplied = new LinkedHashMap<String, String>(request.arguments);
        if ("simulate".equals(spec.id())) {
            String argumentRun = supplied.get("run-id");
            if (argumentRun != null && !argumentRun.trim().isEmpty() && !runId.equals(argumentRun.trim())) {
                throw new IllegalArgumentException("simulate arguments.run-id must match request.runId; use request.runId only");
            }
            supplied.put("run-id", runId);
        }
        if (spec.id().startsWith("history-")) {
            supplied.putIfAbsent("analysis", spec.id().substring("history-".length()));
        }

        CommandBuilder.Command command = CommandBuilder.build(
                spec, hadoopBin, jarPath, supplied, properties);

        JobInstance job = new JobInstance();
        job.runId = runId;
        job.command = spec.id();
        job.module = spec.module().name();
        job.origin = spec.origin().name();
        job.inputUri = request.arguments.get("input");
        job.outputUri = request.arguments.get("out");
        job.argsJson = toJson(command.resolved);
        job.status = JobInstance.Status.SUBMITTING;
        jobs.insert(job);

        try {
            JobExecutor.Handle handle = executor.start(command);
            running.put(job.id, handle);
            jobs.markStarted(job.id, LocalDateTime.now());

            JobAttempt attempt = new JobAttempt();
            attempt.jobInstanceId = job.id;
            attempt.attemptNo = 1;
            attempt.status = "RUNNING";
            attempt.startedAt = LocalDateTime.now();
            attempts.insert(attempt);
            currentAttempt.put(job.id, 1);

            log.info("submitted job {} ({}) run={} pid-alive={}",
                    job.id, spec.id(), runId, handle.isRunning());
            return jobs.findById(job.id);
        } catch (IOException e) {
            jobs.updateStatus(job.id, JobInstance.Status.FAILED, null, null,
                    "无法启动进程：" + e.getMessage(), LocalDateTime.now());
            throw e;
        }
    }

    /**
     * Refreshes every live job.
     *
     * <p>Runs on a daemon thread so a slow ResourceManager never delays an HTTP
     * request.
     */
    void pollAll() {
        for (Map.Entry<Long, JobExecutor.Handle> entry : running.entrySet()) {
            try {
                poll(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                log.warn("poll failed for job {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void poll(Long jobId, JobExecutor.Handle handle) {
        JobInstance job = jobs.findById(jobId);
        if (job == null || job.status == null) {
            return;
        }
        String applicationId = handle.applicationId();
        Integer attemptNo = currentAttempt.get(jobId);

        // A new application id under the same job means the previous round ended
        // and the driver moved on: that is the iterative-training case.
        if (applicationId != null && !applicationId.equals(lastApplicationId.get(jobId))) {
            lastApplicationId.put(jobId, applicationId);
            // The first application id belongs to the attempt row created at
            // submit time; only a *change* of id (a new optimiser round) warrants
            // a new row. Creating one for the first id double-counts the job.
            boolean firstApplication = lastApplicationIdFilled.put(jobId, Boolean.TRUE) == null;
            if (attemptNo != null && !firstApplication) {
                attemptNo = attempts.nextAttemptNo(jobId);
                JobAttempt attempt = new JobAttempt();
                attempt.jobInstanceId = jobId;
                attempt.attemptNo = attemptNo;
                attempt.yarnApplicationId = applicationId;
                attempt.status = "RUNNING";
                attempt.startedAt = LocalDateTime.now();
                attempts.insert(attempt);
                currentAttempt.put(jobId, attemptNo);
            } else if (attemptNo != null) {
                attempts.attachApplication(jobId, attemptNo, applicationId);
            }
        }

        if (applicationId != null && attemptNo != null) {
            YarnMonitor.AppState state = yarn.fetch(applicationId);
            if (state != null) {
                attempts.updateProgress(jobId, attemptNo, state.progress,
                        state.mapProgress, state.reduceProgress, state.state);
            }
        }

        if (handle.isRunning()) {
            return;
        }

        Integer exitCode = handle.exitCode();
        boolean success = exitCode != null && exitCode == 0;
        LocalDateTime finished = LocalDateTime.now();
        String appId = applicationId != null ? applicationId : lastApplicationId.get(jobId);
        String error = success ? null : tail(handle.recentOutput());

        jobs.updateStatus(jobId,
                success ? JobInstance.Status.SUCCEEDED : JobInstance.Status.FAILED,
                appId, exitCode, error, finished);
        if (attemptNo != null) {
            attempts.finishAttempt(jobId, attemptNo,
                    success ? "SUCCEEDED" : "FAILED", finished);
        }
        running.remove(jobId);
        currentAttempt.remove(jobId);
        lastApplicationId.remove(jobId);
        lastApplicationIdFilled.remove(jobId);
        log.info("job {} finished: exit={} status={}", jobId, exitCode,
                success ? "SUCCEEDED" : "FAILED");
    }

    /** Kills a running job. */
    public boolean kill(Long jobId) {
        JobExecutor.Handle handle = running.get(jobId);
        if (handle == null) {
            return false;
        }
        handle.kill();
        return true;
    }

    /** Live console output, or null when the job is no longer held in memory. */
    public String liveOutput(Long jobId) {
        JobExecutor.Handle handle = running.get(jobId);
        return handle == null ? null : handle.recentOutput();
    }

    public boolean isRunning(Long jobId) {
        JobExecutor.Handle handle = running.get(jobId);
        return handle != null && handle.isRunning();
    }

    public int activeCount() {
        return (int) running.values().stream().filter(JobExecutor.Handle::isRunning).count();
    }

    /** Detail beyond what the repository stores, for the status endpoint. */
    public Map<String, Object> runtimeState(Long jobId) {
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        JobExecutor.Handle handle = running.get(jobId);
        state.put("live", handle != null && handle.isRunning());
        state.put("attemptInFlight", currentAttempt.get(jobId));
        if (handle != null && handle.applicationId() != null) {
            YarnMonitor.AppState app = yarn.fetch(handle.applicationId());
            if (app != null) {
                state.put("progress", app.progress);
                state.put("mapProgress", app.mapProgress);
                state.put("reduceProgress", app.reduceProgress);
                state.put("yarnState", app.state);
                state.put("trackingUrl", app.trackingUrl);
            }
        }
        return state;
    }

    private static String generateRunId(JobSpec spec) {
        String prefix = spec.origin() == JobSpec.Origin.HANDOFF ? "api" : "job";
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String tail(String output) {
        if (output == null) {
            return null;
        }
        // Error columns are bounded; keep the end, where the cause is.
        return output.length() <= 1000 ? output : output.substring(output.length() - 1000);
    }

    private static String toJson(Map<String, String> arguments) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Converts a YARN epoch-millis timestamp, tolerating 0. */
    static LocalDateTime fromEpochMillis(long millis) {
        if (millis <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
