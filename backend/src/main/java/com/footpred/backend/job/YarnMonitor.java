package com.footpred.backend.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Reads application state and progress from the YARN ResourceManager.
 *
 * <p>Preferred over scraping the client's console output: the REST endpoint
 * reports a numeric {@code progress} plus separate map and reduce completion,
 * which is what the UI needs to draw a stage bar. Console scraping only yields
 * "map 0% reduce 0%" style lines and loses them between log flushes.
 *
 * <p>Any failure returns an empty snapshot rather than throwing: a progress
 * lookup is advisory, and a transient RM hiccup must not mark a healthy job as
 * failed.
 */
@Component
public class YarnMonitor {

    private static final Logger log = LoggerFactory.getLogger(YarnMonitor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String resourceManagerUrl;

    public YarnMonitor(@Value("${footpred.yarn.rm-url:}") String configured,
                       @Value("${YARN_RM_HOST:127.0.0.1}") String host,
                       @Value("${YARN_RM_PORT:8088}") String port) {
        if (configured != null && !configured.trim().isEmpty()) {
            this.resourceManagerUrl = trimSlash(configured.trim());
        } else {
            this.resourceManagerUrl = "http://" + host + ":" + port;
        }
    }

    /** Snapshot of one application. */
    public static class AppState {
        public String applicationId;
        public String state;
        public String finalStatus;
        public int progress;
        public int mapProgress;
        public int reduceProgress;
        public long startedTime;
        public long finishedTime;
        public String trackingUrl;
        public String diagnostics;

        public boolean isTerminal() {
            return "FINISHED".equals(state) || "FAILED".equals(state) || "KILLED".equals(state);
        }

        public boolean isSuccessful() {
            return "FINISHED".equals(state) && "SUCCEEDED".equals(finalStatus);
        }
    }

    /**
     * Fetches one application, or null when it cannot be read.
     *
     * @param applicationId e.g. {@code application_1792822091296_0042}
     */
    public AppState fetch(String applicationId) {
        if (applicationId == null || applicationId.isEmpty()) {
            return null;
        }
        String url = resourceManagerUrl + "/ws/v1/cluster/apps/" + applicationId;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JsonNode root = JSON.readTree(body.toString()).path("app");
            if (root.isMissingNode()) {
                return null;
            }
            AppState state = new AppState();
            state.applicationId = text(root, "id", applicationId);
            state.state = text(root, "state", null);
            state.finalStatus = text(root, "finalStatus", null);
            state.progress = (int) Math.round(root.path("progress").asDouble(0));
            state.mapProgress = (int) Math.round(root.path("mapProgress").asDouble(0));
            state.reduceProgress = (int) Math.round(root.path("reduceProgress").asDouble(0));
            state.startedTime = root.path("startedTime").asLong(0);
            state.finishedTime = root.path("finishedTime").asLong(0);
            state.trackingUrl = text(root, "trackingUrl", null);
            state.diagnostics = text(root, "diagnostics", null);
            return state;
        } catch (IOException e) {
            log.debug("cannot read {}: {}", url, e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public String resourceManagerUrl() {
        return resourceManagerUrl;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private static String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
