package com.footpred.backend.parser;

import com.footpred.backend.domain.LeagueCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the two model-file conventions without loading them into memory twice.
 *
 * <p>Partner Dixon-Coles models are a single JSON object with a {@code teams}
 * array; handoff random forests are one JSON object per line produced by
 * {@code RandomForestTrainJob}, whose first line is the whole forest. Both are
 * read through Jackson, but the random-forest path only inspects the header of
 * the file so a 100-tree model does not have to be fully materialised just to
 * answer "which version is deployed".
 */
public final class ModelFileParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelFileParser() {
    }

    /** Summary of a Dixon-Coles model.json. */
    public static class DixonColesModelInfo {
        public String modelType;
        public int version;
        public String scoreConvention;
        public int teamCount;
        public double homeAdvantage;
        public double rho;
        public long referenceEpochDay;
        public double halfLifeDays;
        public Double sigma2;
        public int iterations;
        public Double finalLogLikelihood;
        public String trainedAt;
        public List<String> teams = new ArrayList<String>();
    }

    /** Summary of a random-forest model directory part file. */
    public static class RandomForestModelInfo {
        public String modelVersion;
        public String featureSchemaVersion;
        public int treeCount;
        public int featureCount;
        public List<String> featureNames = new ArrayList<String>();
        public Map<String, Object> parameters = new LinkedHashMap<String, Object>();
    }

    /** Summary of a LR-xG logistic model.json. */
    public static class PlayerXgModelInfo {
        public String version;
        public int featureCount;
        public int coefficientCount;
        public List<String> featureNames = new ArrayList<String>();
        public Double intercept;
    }

    /**
     * Reads the module-3 LR-xG model.
     *
     * <p>Its schema is unrelated to the Dixon-Coles one: there is no team roster
     * and no {@code modelType}, just a version string, the 16 feature names and
     * the matching coefficient vector, with the intercept as element zero.
     */
    public static PlayerXgModelInfo readPlayerXg(InputStream input) throws IOException {
        JsonNode root = JSON.readTree(input);
        PlayerXgModelInfo info = new PlayerXgModelInfo();
        info.version = text(root, "version");
        JsonNode names = root.path("featureNames");
        if (names.isArray()) {
            for (JsonNode name : names) {
                info.featureNames.add(name.asText());
            }
        }
        info.featureCount = info.featureNames.size();
        JsonNode coefficients = root.path("coefficients");
        info.coefficientCount = coefficients.isArray() ? coefficients.size() : 0;
        if (info.coefficientCount > 0 && coefficients.get(0).isNumber()) {
            info.intercept = coefficients.get(0).asDouble();
        }
        return info;
    }

    public static DixonColesModelInfo readDixonColes(InputStream input) throws IOException {
        JsonNode root = JSON.readTree(input);
        DixonColesModelInfo info = new DixonColesModelInfo();
        info.modelType = text(root, "modelType");
        info.version = root.path("version").asInt(0);
        info.scoreConvention = text(root, "scoreConvention");
        info.teamCount = root.path("nTeams").asInt(0);
        info.homeAdvantage = root.path("homeAdv").asDouble(Double.NaN);
        info.rho = root.path("rho").asDouble(Double.NaN);
        info.referenceEpochDay = root.path("refEpochDay").asLong(0L);
        info.halfLifeDays = root.path("halfLifeDays").asDouble(Double.NaN);
        if (root.hasNonNull("sigma2")) {
            info.sigma2 = root.path("sigma2").asDouble();
        }
        info.iterations = root.path("iterations").asInt(0);
        if (root.hasNonNull("finalLL")) {
            info.finalLogLikelihood = root.path("finalLL").asDouble();
        }
        info.trainedAt = text(root, "trainedAt");
        JsonNode teams = root.path("teams");
        if (teams.isArray()) {
            for (JsonNode team : teams) {
                info.teams.add(text(team, "name"));
            }
        }
        return info;
    }

    /**
     * Reads the first JSON document of a random-forest part file.
     *
     * <p>The trainer writes exactly one line, but the stream is read as a single
     * document rather than line-by-line so a pretty-printed file also works.
     */
    public static RandomForestModelInfo readRandomForest(InputStream input) throws IOException {
        JsonNode root = JSON.readTree(input);
        RandomForestModelInfo info = new RandomForestModelInfo();
        info.modelVersion = text(root, "modelVersion");
        JsonNode names = root.path("featureNames");
        if (names.isArray()) {
            for (JsonNode name : names) {
                info.featureNames.add(name.asText());
            }
        }
        info.featureCount = info.featureNames.size();
        JsonNode trees = root.path("trees");
        info.treeCount = trees.isArray() ? trees.size() : 0;
        JsonNode params = root.path("parameters");
        if (params.isObject()) {
            info.featureSchemaVersion = params.hasNonNull("featureSchemaVersion")
                    ? params.path("featureSchemaVersion").asText()
                    : null;
            java.util.Iterator<String> fields = params.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode value = params.path(field);
                if (value.isNumber()) {
                    info.parameters.put(field, value.asDouble());
                } else if (value.isBoolean()) {
                    info.parameters.put(field, value.asBoolean());
                } else if (value.isTextual()) {
                    info.parameters.put(field, value.asText());
                }
            }
        }
        return info;
    }

    /** League-to-team roster derived from the partner helper TSV. */
    public static Map<String, List<String>> readTeamsByLeague(List<String> lines) {
        Map<String, List<String>> roster = new LinkedHashMap<String, List<String>>();
        for (String line : lines) {
            if (Tsv.isSkippable(line)) {
                continue;
            }
            String[] f = Tsv.split(line);
            if (f.length < 3) {
                continue;
            }
            String league = LeagueCode.canonical(f[0]);
            List<String> teams = roster.get(league);
            if (teams == null) {
                teams = new ArrayList<String>();
                roster.put(league, teams);
            }
            teams.add(f[1]);
        }
        return Collections.unmodifiableMap(roster);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
