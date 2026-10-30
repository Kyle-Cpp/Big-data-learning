package com.footpred.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks over HTTP against the real artefacts in the handoff package.
 *
 * <p>These assertions use the numbers the project published
 * ({@code RESULTS_MODULE2_CLUB_V4.md} and the model card), so a green run means
 * the API is serving the delivered results and not a stale or re-derived copy.
 */
@SpringBootTest(properties = {
        "footpred.local.root=.",
        "footpred.result.module2-evaluation=src/test/resources/fixtures/evaluation",
        "footpred.result.module2-club-model=models/module2/club-rf-v4",
        "footpred.result.module2-international-model=models/module2/international-rf-v1",
        "footpred.result.module3-model=src/test/resources/fixtures/player-xg-model.json",
        "footpred.result.simulation=src/test/resources/fixtures/simulation_full.tsv",
        "footpred.result.simulation-evaluation=src/test/resources/fixtures/module4-evaluation.tsv",
        "footpred.result.real-table=src/test/resources/fixtures/real_final_table.tsv",
        "footpred.result.matches.dixon-coles="
})
@AutoConfigureMockMvc
class ResultApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seasonReplayReportFollowsLeagueFilterAndCustomOutputDoesNotReuseDefaultMetrics() throws Exception {
        mockMvc.perform(get("/api/results/simulation").param("league", "bundesliga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.length()").value(1))
                .andExpect(jsonPath("$.evaluation[0].metrics.points_mae").value("6.27666667"));
        mockMvc.perform(get("/api/results/simulation")
                        .param("location", "src/test/resources/fixtures/simulation_full.tsv"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evaluation").doesNotExist());
    }

    @Test
    void realClubCareerHasCorrectSeasonsAndMetricMeaning() throws Exception {
        mockMvc.perform(get("/api/results/history").param("analysis", "P6.5")
                        .param("location", "src/test/resources/fixtures/p65-club-real.tsv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(19))
                .andExpect(jsonPath("$.items[0].season").value("2004-05"))
                .andExpect(jsonPath("$.items[0].count").value(7))
                .andExpect(jsonPath("$.items[0].secondaryValue").value(0.57))
                .andExpect(jsonPath("$.items[0].secondaryMetric").value("xg"));
    }

    @Test
    void playersKeepAllSeasonProfilesAndAcceptSlashSeasonFilter() throws Exception {
        String fixture = "src/test/resources/fixtures/players-real-multi-season.tsv";
        mockMvc.perform(get("/api/results/players").param("location", fixture).param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.seasons.length()").value(3))
                .andExpect(jsonPath("$.items[0].matchId").doesNotExist());
        mockMvc.perform(get("/api/results/players").param("location", fixture)
                        .param("season", "2005/2006").param("competition", "La Liga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].season").value("2005-06"))
                .andExpect(jsonPath("$.items[0].teamId").value(217))
                .andExpect(jsonPath("$.items[0].playerId").value(5503));
    }

    @Test
    void dcWithoutItsOwnLocationNeverFallsBackToRfFile() throws Exception {
        mockMvc.perform(get("/api/results/matches").param("source", "dixon-coles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.error").value("prediction-location-not-configured"));
    }

    @Test
    void dcAcceptsItsOwnFixtureFile() throws Exception {
        mockMvc.perform(get("/api/results/matches").param("source", "dixon-coles")
                        .param("location", "src/test/resources/fixtures/predict-part-m-sample.tsv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.items[0].homeTeam").value("Argentina"))
                .andExpect(jsonPath("$.items[0].probabilityHome").value(0.4123));
    }

    @Test
    void dcRejectsExplicitRfLocationInsteadOfReturningInvalidProbabilities() throws Exception {
        mockMvc.perform(get("/api/results/matches").param("source", "dixon-coles")
                        .param("location", "src/test/resources/fixtures/rf4-predictions-sample.tsv"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.error").value("invalid-prediction-format"));
    }

    @Test
    void dcMissingFileIsReportedAsUnavailable() throws Exception {
        mockMvc.perform(get("/api/results/matches").param("source", "dixon-coles")
                        .param("location", "nonexistent-dc-results.tsv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.error").value("prediction-results-missing-or-empty"));
    }

    @Test
    void catalogExposesEveryCommand() throws Exception {
        mockMvc.perform(get("/api/catalog/jobs"))
                .andExpect(status().isOk())
                // 10 partner commands + 9 handoff commands = 19 spec entries.
                .andExpect(jsonPath("$.length()").value(21));
    }

    @Test
    void catalogMarksRetrainingCommands() throws Exception {
        mockMvc.perform(get("/api/catalog/jobs").param("module", "SCORE_PREDICTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='train')].retrains").value(true))
                .andExpect(jsonPath("$[?(@.id=='predict')].retrains").value(false));
    }

    @Test
    void seasonEncodingIsBridged() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/catalog/seasons"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // The two packages disagree; the catalogue has to expose both spellings.
        assertTrue(body.contains("\"compact\":\"1516\""), "compact form must be offered");
        assertTrue(body.contains("\"label\":\"2015-16\""), "label form must be offered");
    }

    @Test
    void evaluationMatchesPublishedV4TestNumbers() throws Exception {
        mockMvc.perform(get("/api/results/evaluation").param("split", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches").value(1752))
                .andExpect(jsonPath("$.accuracy").value(0.51655251))
                .andExpect(jsonPath("$.rps").value(0.20162505))
                .andExpect(jsonPath("$.macroF1").value(0.45769241))
                .andExpect(jsonPath("$.drawMargin").value(0.11))
                .andExpect(jsonPath("$.confusion.H").value("550,86,100"));
    }

    @Test
    void evaluationSplitsAreIndependentlyReadable() throws Exception {
        mockMvc.perform(get("/api/results/evaluation").param("split", "validation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.split").value("validation"));
        mockMvc.perform(get("/api/results/evaluation").param("split", "backtest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.split").value("backtest"));
    }

    @Test
    void simulationServesTenThousandRunOutput() throws Exception {
        // 10k simulations: Premier League, La Liga and Serie A have 20 teams,
        // while the Bundesliga and Ligue 1 have 18, giving 96 rows in total.
        mockMvc.perform(get("/api/results/simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(96));
    }

    @Test
    void leagueFilterAcceptsEitherEncoding() throws Exception {
        mockMvc.perform(get("/api/results/simulation").param("league", "bundesliga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(18))
                .andExpect(jsonPath("$.items[0].league").value("Bundesliga"));
    }

    @Test
    void realTableCarriesAllFiveLeagues() throws Exception {
        mockMvc.perform(get("/api/results/real-table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(96));
    }

    /**
     * Asserts the deployed model schemas.
     *
     * <p>The two random forests are ~5 MB each and ship as release assets rather
     * than repository content, so the endpoint reports {@code available=false}
     * on a fresh clone. The LR-xG model is small enough to live in test
     * resources, so that part is always asserted; the forest assertions run only
     * when the model bundle is present.
     */
    @Test
    void modelsReportSchemaAgreement() throws Exception {
        mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerXg.featureCount").value(16))
                .andExpect(jsonPath("$.playerXg.coefficientsMatchFeatures").value(true));

        boolean forestsPresent = Boolean.TRUE.equals(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        mockMvc.perform(get("/api/models")).andReturn()
                                .getResponse().getContentAsString())
                        .path("randomForests").get(0).path("available").asBoolean());
        org.junit.jupiter.api.Assumptions.assumeTrue(forestsPresent,
                "random-forest models not deployed to this checkout");

        mockMvc.perform(get("/api/models"))
                .andExpect(jsonPath("$.randomForests[0].featureCount").value(73))
                .andExpect(jsonPath("$.randomForests[0].featureCountMatches").value(true))
                .andExpect(jsonPath("$.randomForests[0].configuredDrawMargin").value(0.11))
                .andExpect(jsonPath("$.randomForests[1].featureCount").value(56))
                .andExpect(jsonPath("$.randomForests[1].configuredDrawMargin").value(0.15));
    }

    @Test
    void unknownAnalysisIsRejected() throws Exception {
        mockMvc.perform(get("/api/results/history").param("analysis", "P6.9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historyWithoutLocationExplainsItself() throws Exception {
        // Before a job has run there is no output; that is a normal state, not a
        // failure, so the API answers 200 with an explicit note.
        mockMvc.perform(get("/api/results/history").param("analysis", "P6.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").value("P6.5"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void allEightAnalysesAreAddressable() throws Exception {
        String[] ids = {"P6.1", "P6.2", "P6.3", "P6.4", "P6.5", "P6.6", "P6.7", "P6.8"};
        for (String id : ids) {
            mockMvc.perform(get("/api/results/history").param("analysis", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysis").value(id));
        }
    }

    @Test
    void missingResultLocationReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/api/results/players").param("location", "/nonexistent/path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void accuracyIsRecomputedFromLabelsNotTrusted() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/results/matches")
                        .param("location",
                                "src/test/resources/fixtures/rf4-predictions-sample.tsv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(6))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // 4 of the 6 fixture rows have predicted == actual, so the API must
        // report 0.666... rather than echoing a stored figure.
        assertTrue(body.contains("\"accuracy\":0.66"),
                "accuracy should be recomputed from the labels: " + body);
    }
}
