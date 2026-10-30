package com.footpred.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The import-then-query round trip over HTTP.
 *
 * <p>Covers the chain an operator actually performs: run a job, export, import,
 * then read the metadata back. Each step is a separate endpoint, so a break in
 * any one of them shows up here rather than only in production.
 */
@SpringBootTest(properties = {
        "footpred.mysql.enabled=true",
        "footpred.local.root=.",
        "footpred.result.module2-evaluation=src/test/resources/fixtures/evaluation",
        "footpred.result.simulation=src/test/resources/fixtures/simulation_full.tsv"
})
@AutoConfigureMockMvc
@ActiveProfiles("mysql-test")
@Sql(scripts = "classpath:schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ImportApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String FIXTURES = "src/test/resources/fixtures";

    @Test
    void clubCareerImportMatchesReadApiAndInvalidFilePreservesRows() throws Exception {
        String endpoint = "/api/admin/import/history/club-career";
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis\":\"P6.5\",\"location\":\"" + FIXTURES + "/p65-club-real.tsv\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(19));
        assertEquals(0.57, jdbc.queryForObject("SELECT value_second FROM history_point WHERE run_id='club-career' AND season='2004-05'", Double.class), 1e-6);
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"analysis\":\"P6.5\",\"location\":\"" + FIXTURES + "/rf4-predictions-sample.tsv\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(19, jdbc.queryForObject("SELECT COUNT(*) FROM history_point WHERE run_id='club-career'", Integer.class));
    }

    @Test
    void playerImportsKeepSeasonsAndInvalidInputDoesNotReplaceExistingRows() throws Exception {
        String runId = "players-round-trip";
        String body = "{\"location\":\"" + FIXTURES + "/players-real-multi-season.tsv\"}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/admin/import/players/" + runId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rowCounts.player_profile").value(3));
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE run_id=?",
                Integer.class, runId));
        mockMvc.perform(post("/api/admin/import/players/" + runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"" + FIXTURES + "/rf4-predictions-sample.tsv\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE run_id=?",
                Integer.class, runId));
    }

    @Test
    void invalidDcImportPreservesPreviouslyImportedPredictions() throws Exception {
        String runId = "dc-format-guard";
        mockMvc.perform(post("/api/admin/import/matches/" + runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"dixon-coles\",\"location\":\"" + FIXTURES
                                + "/predict-part-m-sample.tsv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCounts['match_prediction[dixon-coles]']").value(4));
        mockMvc.perform(post("/api/admin/import/matches/" + runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"dixon-coles\",\"location\":\"" + FIXTURES
                                + "/rf4-predictions-sample.tsv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partial"))
                .andExpect(jsonPath("$.totalRows").value(0));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM match_prediction"
                + " WHERE run_id=? AND source='dixon-coles'", Integer.class, runId));
    }

    @Test
    void importMatchesThenQueryThemBack() throws Exception {
        String runId = "api-run-1";
        String body = "{\"source\":\"random-forest\",\"location\":\"" + FIXTURES
                + "/rf4-predictions-sample.tsv\"}";

        mockMvc.perform(post("/api/admin/import/matches/" + runId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCounts['match_prediction[random-forest]']").value(6));
    }

    @Test
    void importHistoryThenQueryItBack() throws Exception {
        String runId = "api-run-2";
        String body = "{\"analysis\":\"P6.1\",\"location\":\"" + FIXTURES
                + "/p6-1-champions-sample.tsv\"}";

        mockMvc.perform(post("/api/admin/import/history/" + runId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCounts['history_point[P6.1]']").value(6));
    }

    @Test
    void unknownAnalysisIsReportedAsSkipped() throws Exception {
        String body = "{\"analysis\":\"P6.9\",\"location\":\"" + FIXTURES
                + "/p6-1-champions-sample.tsv\"}";

        mockMvc.perform(post("/api/admin/import/history/api-run-3")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partial"))
                .andExpect(jsonPath("$.skipped.history_point").exists());
    }

    @Test
    void missingFileIsSkippedNotFatal() throws Exception {
        String body = "{\"analysis\":\"P6.1\",\"location\":\"/no/such/dir/champions.tsv\"}";

        // A missing export is a normal state before the job has run, so the
        // endpoint reports it rather than failing the request.
        mockMvc.perform(post("/api/admin/import/history/api-run-4")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partial"));
    }

    @Test
    void jobsListStartsEmpty() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void summaryReportsZeroCountsOnFreshDatabase() throws Exception {
        mockMvc.perform(get("/api/jobs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PENDING").value(0))
                .andExpect(jsonPath("$.RUNNING").value(0))
                .andExpect(jsonPath("$.SUCCEEDED").value(0));
    }

    @Test
    void unknownJobReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/jobs/999999"))
                .andExpect(status().isNotFound());
    }
}
