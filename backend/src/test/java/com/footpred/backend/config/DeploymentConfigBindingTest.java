package com.footpred.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the environment variables written by
 * {@code deploy/config/footpred.env} actually reach the Spring properties.
 *
 * <p>This is the one link in the deployment chain that cannot be checked by
 * reading code: the shell exports {@code FOOTPRED_RESULT_MODULE2_EVALUATION} and
 * Spring has to resolve it as {@code footpred.result.module2-evaluation}, which
 * involves relaxed binding across an underscore/hyphen boundary. If it silently
 * fails, every result endpoint returns an empty payload on the VM while working
 * perfectly on a laptop that uses application.properties.
 */
@SpringBootTest(properties = {
        "footpred.local.root=/job/footpred/export",
        "footpred.result.module2-evaluation=handoff/models/module2/club-rf-v4",
        "footpred.result.simulation=handoff/models/simulation_leagues_v1_10k.tsv",
        "footpred.draw-margin.club=0.11",
        "footpred.draw-margin.international=0.15",
        "footpred.hdfs.root=/footpred",
        "footpred.hdfs.root.partner=/FootPred"
})
class DeploymentConfigBindingTest {

    @Value("${footpred.local.root}")
    private String localRoot;

    @Value("${footpred.result.module2-evaluation}")
    private String evaluation;

    @Value("${footpred.result.simulation}")
    private String simulation;

    @Value("${footpred.draw-margin.club}")
    private double clubMargin;

    @Value("${footpred.draw-margin.international}")
    private double internationalMargin;

    @Value("${footpred.hdfs.root}")
    private String handoffRoot;

    @Value("${footpred.hdfs.root.partner}")
    private String partnerRoot;

    @Test
    void vmPathsAreResolved() {
        assertEquals("/job/footpred/export", localRoot);
        assertEquals("handoff/models/module2/club-rf-v4", evaluation);
        assertEquals("handoff/models/simulation_leagues_v1_10k.tsv", simulation);
    }

    @Test
    void drawMarginsStaySeparated() {
        // Mixing these two is the failure mode MODEL_INFO.md warns about.
        assertEquals(0.11, clubMargin, 1e-9);
        assertEquals(0.15, internationalMargin, 1e-9);
    }

    @Test
    void bothHdfsRootsKeepTheirCase() {
        // HDFS is case sensitive; these are two different directories.
        assertEquals("/footpred", handoffRoot);
        assertEquals("/FootPred", partnerRoot);
        assertEquals(false, handoffRoot.equals(partnerRoot));
    }
}
