package com.footpred.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The submission endpoint's contract, without launching anything.
 *
 * <p>Job execution is disabled in this context on purpose: what is under test is
 * how the API behaves when the capability is off, and how it validates input
 * before a process is ever created. Actually starting a job is covered by
 * {@code JobServiceTest} with a stub binary.
 */
@SpringBootTest(properties = {
        "footpred.mysql.enabled=true",
        "footpred.job.enabled=false",
        "footpred.local.root=."
})
@AutoConfigureMockMvc
@ActiveProfiles("mysql-test")
@Sql(scripts = "classpath:schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class JobSubmissionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submissionIsRefusedWhenExecutionDisabled() throws Exception {
        String body = "{\"command\":\"club-predict\",\"arguments\":{}}";

        // 503 rather than 404: the endpoint exists, the capability does not.
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("job-disabled"));
    }

    @Test
    void killIsRefusedWhenExecutionDisabled() throws Exception {
        mockMvc.perform(post("/api/jobs/1/kill"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void listingStillWorksWithoutExecution() throws Exception {
        // A read-only viewer keeps working: submission and monitoring are
        // separable capabilities.
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void summaryExposesStatusBuckets() throws Exception {
        mockMvc.perform(get("/api/jobs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.PENDING").exists())
                .andExpect(jsonPath("$.SUCCEEDED").exists())
                .andExpect(jsonPath("$.FAILED").exists());
    }

    @Test
    void unknownJobLogIs404() throws Exception {
        mockMvc.perform(get("/api/jobs/424242/log"))
                .andExpect(status().isNotFound());
    }

    @Test
    void importEndpointsArePresent() throws Exception {
        // A missing file is reported, not thrown, so this returns 200 with a
        // "partial" status — proving the endpoint is wired.
        String body = "{\"analysis\":\"P6.1\",\"location\":\"/no/such/file.tsv\"}";
        mockMvc.perform(post("/api/admin/import/history/api-check")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partial"));
    }
}
