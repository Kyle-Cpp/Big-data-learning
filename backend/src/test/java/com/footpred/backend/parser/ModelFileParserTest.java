package com.footpred.backend.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads the three deployed models and asserts the invariants the packages warn
 * about: a forest matches its feature schema, and the LR-xG coefficients line up
 * with its feature names.
 *
 * <p>The LR-xG model is tiny (577 bytes) and ships with this repository, so its
 * tests always run. The two random forests are ~5 MB each and are distributed as
 * release assets rather than source, so those tests locate them through the
 * {@code footpred.models.dir} system property (or a few conventional paths) and
 * skip cleanly when absent. A clone without the model bundle therefore still
 * builds green instead of failing on a missing file.
 */
class ModelFileParserTest {

    /** Overridable so CI or a different checkout layout can point at the models. */
    private static final String MODELS_DIR_PROPERTY = "footpred.models.dir";

    /**
     * Candidate locations, most specific first.
     *
     * <p>The relative entries assume this project sits beside the algorithm
     * packages, which is how the delivery bundle is laid out.
     */
    private static final String[] CANDIDATES = {
            "../footpred-handoff-fixed/models",
            "../../footpred-handoff-fixed/models",
            "models"
    };

    private Path modelsRoot() {
        String configured = System.getProperty(MODELS_DIR_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            Path path = Paths.get(configured.trim());
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        for (String candidate : CANDIDATES) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    private boolean forestAvailable() {
        Path root = modelsRoot();
        return root != null
                && Files.isRegularFile(root.resolve("module2/club-rf-v4/part-r-00000"))
                && Files.isRegularFile(root.resolve("module2/international-rf-v1/part-r-00000"));
    }

    private InputStream openForest(String relative) throws IOException {
        return Files.newInputStream(modelsRoot().resolve(relative));
    }

    // ---------------------------------------------------- always-run tests

    @Test
    void playerXgModelHasOneCoefficientPerFeature() throws IOException {
        // Shipped in src/test/resources, so no assumption guard is needed.
        InputStream stream = getClass().getResourceAsStream("/fixtures/player-xg-model.json");
        assertNotNull(stream, "fixture model must be on the test classpath");
        try {
            ModelFileParser.PlayerXgModelInfo info = ModelFileParser.readPlayerXg(stream);
            assertEquals("big-five-xg-v1", info.version);
            assertEquals(16, info.featureCount);
            assertEquals(16, info.coefficientCount);
            assertEquals(info.featureCount, info.coefficientCount);
            assertNotNull(info.intercept);
            assertTrue(info.featureNames.contains("log_distance"));
            assertTrue(info.featureNames.contains("angle"));
        } finally {
            stream.close();
        }
    }

    // ------------------------------------------------- conditional tests

    @Test
    void clubForestMatchesDrawAwareV3Schema() throws IOException {
        assumeTrue(forestAvailable(),
                "random-forest models not present; set -D" + MODELS_DIR_PROPERTY + "=<dir> to run");
        InputStream stream = openForest("module2/club-rf-v4/part-r-00000");
        try {
            ModelFileParser.RandomForestModelInfo info = ModelFileParser.readRandomForest(stream);
            assertEquals("3.0", info.modelVersion);
            assertEquals("draw-aware-v3", info.featureSchemaVersion);
            assertEquals(100, info.treeCount);
            // The schema is 73 features; a different count means a stale model.
            assertEquals(73, info.featureCount);
            assertEquals(73, info.featureNames.size());
            assertTrue(info.featureNames.contains("elo_diff"));
            assertTrue(info.featureNames.contains("home_sot_for"));
        } finally {
            stream.close();
        }
    }

    @Test
    void internationalForestUsesItsOwnSchema() throws IOException {
        assumeTrue(forestAvailable(),
                "random-forest models not present; set -D" + MODELS_DIR_PROPERTY + "=<dir> to run");
        InputStream stream = openForest("module2/international-rf-v1/part-r-00000");
        try {
            ModelFileParser.RandomForestModelInfo info = ModelFileParser.readRandomForest(stream);
            assertEquals("international-v1", info.featureSchemaVersion);
            assertEquals(56, info.featureCount);
            assertTrue(info.featureNames.size() != 73,
                    "international model must not carry the club feature set");
        } finally {
            stream.close();
        }
    }

    @Test
    void forestParametersExposeTheTrainingRecipe() throws IOException {
        assumeTrue(forestAvailable(),
                "random-forest models not present; set -D" + MODELS_DIR_PROPERTY + "=<dir> to run");
        InputStream stream = openForest("module2/club-rf-v4/part-r-00000");
        try {
            ModelFileParser.RandomForestModelInfo info = ModelFileParser.readRandomForest(stream);
            assertEquals(8.0, ((Number) info.parameters.get("maxDepth")).doubleValue(), 1e-9);
            // Class weighting for draws is what makes the draw margin meaningful.
            assertEquals(1.4, ((Number) info.parameters.get("classWeightDraw")).doubleValue(), 1e-9);
        } finally {
            stream.close();
        }
    }
}
