package com.footpred.backend.api;

import com.footpred.backend.parser.ModelFileParser;
import com.footpred.backend.service.ResultReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports which models are deployed.
 *
 * <p>Both packages bind a model to a matching feature schema and warn that
 * mixing versions is invalid; the check is manual in the JARs. Exposing the
 * version, feature count and schema here lets the front end display them and lets
 * an operator notice a mismatch before a demo instead of after one.
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ResultReader reader;

    @Value("${footpred.result.module2-club-model:}")
    private String clubModelLocation;

    @Value("${footpred.result.module2-international-model:}")
    private String internationalModelLocation;

    @Value("${footpred.result.module3-model:}")
    private String playerModelLocation;

    @Value("${footpred.result.teams-by-league:}")
    private String teamsByLeagueLocation;

    /**
     * Draw margin is a fixed inference threshold, not a field in the model file:
     * the handoff package keeps it in {@code config/handoff.env} (0.11 for the
     * club forest, 0.15 for the international one). Reporting it from the
     * environment keeps the API honest about where the value came from.
     */
    @Value("${footpred.draw-margin.club:0.11}")
    private double clubDrawMargin;

    @Value("${footpred.draw-margin.international:0.15}")
    private double internationalDrawMargin;

    public ModelController(ResultReader reader) {
        this.reader = reader;
    }

    @GetMapping
    public Map<String, Object> models() throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();

        List<Map<String, Object>> forests = new ArrayList<Map<String, Object>>();
        forests.add(describeForest("club", "模块二俱乐部随机森林", clubModelLocation, 73,
                clubDrawMargin));
        forests.add(describeForest("international", "模块二国家队随机森林",
                internationalModelLocation, 56, internationalDrawMargin));
        payload.put("randomForests", forests);

        payload.put("playerXg", describePlayerXg(playerModelLocation));
        return payload;
    }

    private Map<String, Object> describeForest(String key, String label, String location,
                                               int expectedFeatureCount,
                                               double configuredDrawMargin) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", key);
        item.put("label", label);
        item.put("location", location);
        item.put("configuredDrawMargin", configuredDrawMargin);
        item.put("drawMarginSource", "config (not stored in the model file)");
        InputStream stream = null;
        try {
            stream = reader.open(partFile(location));
            ModelFileParser.RandomForestModelInfo info = ModelFileParser.readRandomForest(stream);
            item.put("modelVersion", info.modelVersion);
            item.put("featureSchemaVersion", info.featureSchemaVersion);
            item.put("treeCount", info.treeCount);
            item.put("featureCount", info.featureCount);
            item.put("expectedFeatureCount", expectedFeatureCount);
            // A mismatch here means a model trained on a different feature set,
            // which the JARs cannot detect on their own.
            item.put("featureCountMatches", info.featureCount == expectedFeatureCount);
            item.put("parameters", info.parameters);
            item.put("available", true);
        } catch (IOException e) {
            item.put("available", false);
            item.put("error", e.getMessage());
        } finally {
            closeQuietly(stream);
        }
        return item;
    }

    private Map<String, Object> describePlayerXg(String location) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("location", location);
        item.put("expectedFeatureCount", 16);
        InputStream stream = null;
        try {
            stream = reader.open(location);
            ModelFileParser.PlayerXgModelInfo info = ModelFileParser.readPlayerXg(stream);
            item.put("version", info.version);
            item.put("featureCount", info.featureCount);
            item.put("coefficientCount", info.coefficientCount);
            // Coefficients must line up 1:1 with features or scoring silently
            // misaligns every shot.
            item.put("coefficientsMatchFeatures", info.featureCount == info.coefficientCount);
            item.put("featureNames", info.featureNames);
            item.put("intercept", info.intercept);
            item.put("available", true);
        } catch (IOException e) {
            item.put("available", false);
            item.put("error", e.getMessage());
        } finally {
            closeQuietly(stream);
        }
        return item;
    }

    /**
     * Resolves the data file inside a model directory.
     *
     * <p>A random-forest model is a directory holding {@code part-r-00000}; a
     * Dixon-Coles or LR-xG model is a single {@code model.json}.
     */
    private String partFile(String location) throws IOException {
        if (location == null || location.trim().isEmpty()) {
            throw new IOException("model location is not configured");
        }
        List<String> parts = reader.listPartFiles(location.trim());
        if (!parts.isEmpty()) {
            return parts.get(0);
        }
        return location.trim().endsWith(".json") ? location.trim() : location.trim() + "/model.json";
    }

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // read-only handle, nothing to recover
        }
    }
}
