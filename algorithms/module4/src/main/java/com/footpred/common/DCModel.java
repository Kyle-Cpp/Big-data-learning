package com.footpred.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Dixon-Coles parameter vector.
 *
 *     eta_home = attack[i] + defense[j] + homeAdv * (1 - neutral)
 *     eta_away = attack[j] + defense[i]
 *     lambda   = exp(eta_home)   (home expected goals, extra time included)
 *     mu       = exp(eta_away)   (away expected goals)
 *
 * Identifiability: attack and defence are only determined up to a common shift,
 * so the driver re-centres them to zero mean after every update
 * ({@link #projectAndClip()}).
 *
 * Two serialisations exist:
 *  - Base64 binary blob, broadcast through the Job Configuration during
 *    iterative training (compact, no parser overhead per mapper).
 *  - JSON document, persisted to HDFS as the contract with downstream
 *    consumers (team-service / web layer read this file directly).
 */
public class DCModel {

    public static final int ETA_CLIP = 12;
    public static final double RHO_MIN = -0.15;
    public static final double RHO_MAX = 0.15;

    public final int nTeams;
    public double[] attack;
    public double[] defense;
    public double homeAdv;
    public double rho;
    public long refEpochDay;
    public final List<String> teamNames;

    // ---- training metadata (persisted to JSON only) ----
    public double halfLifeDays = Double.NaN;
    public double sigma2 = 0.0; // shrinkage prior variance; 0 = disabled
    public int iterations;
    public double finalLL = Double.NaN;
    public double weightedCount = Double.NaN;
    public String trainedAt;

    public DCModel(int nTeams, long refEpochDay) {
        this.nTeams = nTeams;
        this.attack = new double[nTeams];
        this.defense = new double[nTeams];
        this.refEpochDay = refEpochDay;
        this.teamNames = new ArrayList<>(nTeams);
    }

    // ------------------------------------------------------------------ //
    //  Linear predictors and intensities                                 //
    // ------------------------------------------------------------------ //

    public double etaHome(int homeIdx, int awayIdx, boolean neutral) {
        double eta = attack[homeIdx] + defense[awayIdx] + (neutral ? 0.0 : homeAdv);
        return clip(eta);
    }

    public double etaAway(int homeIdx, int awayIdx) {
        return clip(attack[awayIdx] + defense[homeIdx]);
    }

    public double lambdaHome(int homeIdx, int awayIdx, boolean neutral) {
        return Math.exp(etaHome(homeIdx, awayIdx, neutral));
    }

    public double lambdaAway(int homeIdx, int awayIdx) {
        return Math.exp(etaAway(homeIdx, awayIdx));
    }

    private static double clip(double v) {
        return v < -ETA_CLIP ? -ETA_CLIP : (v > ETA_CLIP ? ETA_CLIP : v);
    }

    /**
     * Re-centres attack/defence to zero mean (identifiability projection) and
     * keeps rho inside the admissible range used by this implementation.
     */
    public void projectAndClip() {
        centre(attack);
        centre(defense);
        if (rho < RHO_MIN) {
            rho = RHO_MIN;
        } else if (rho > RHO_MAX) {
            rho = RHO_MAX;
        }
    }

    private static void centre(double[] v) {
        double mean = 0.0;
        for (double x : v) {
            mean += x;
        }
        mean /= v.length;
        for (int i = 0; i < v.length; i++) {
            v[i] -= mean;
        }
    }

    // ------------------------------------------------------------------ //
    //  Flat view used by the optimiser (driver side)                     //
    // ------------------------------------------------------------------ //

    /** flat = [attack(0..n-1), defense(0..n-1), homeAdv, rho], length 2n+2. */
    public double[] toFlat() {
        double[] flat = new double[2 * nTeams + 2];
        System.arraycopy(attack, 0, flat, 0, nTeams);
        System.arraycopy(defense, 0, flat, nTeams, nTeams);
        flat[2 * nTeams] = homeAdv;
        flat[2 * nTeams + 1] = rho;
        return flat;
    }

    public static int flatDim(int nTeams) {
        return 2 * nTeams + 2;
    }

    /** Fills parameters from a flat vector, then applies projection/clipping. */
    public void setFlat(double[] flat) {
        if (flat.length != flatDim(nTeams)) {
            throw new IllegalArgumentException(
                    "flat length " + flat.length + " != " + flatDim(nTeams));
        }
        System.arraycopy(flat, 0, attack, 0, nTeams);
        System.arraycopy(flat, nTeams, defense, 0, nTeams);
        homeAdv = flat[2 * nTeams];
        rho = flat[2 * nTeams + 1];
        projectAndClip();
    }

    public void initialTheta() {
        java.util.Arrays.fill(attack, 0.1);
        java.util.Arrays.fill(defense, 0.0);
        homeAdv = 0.25;
        rho = -0.05;
        projectAndClip();
    }

    // ------------------------------------------------------------------ //
    //  Base64 serialisation (configuration broadcast)                    //
    // ------------------------------------------------------------------ //

    public String toBase64() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(nTeams);
        out.writeLong(refEpochDay);
        for (double v : attack) {
            out.writeDouble(v);
        }
        for (double v : defense) {
            out.writeDouble(v);
        }
        out.writeDouble(homeAdv);
        out.writeDouble(rho);
        for (String name : teamNames) {
            out.writeUTF(name);
        }
        out.flush();
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    public static DCModel fromBase64(String base64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int n = in.readInt();
        long refEpochDay = in.readLong();
        DCModel model = new DCModel(n, refEpochDay);
        for (int i = 0; i < n; i++) {
            model.attack[i] = in.readDouble();
        }
        for (int i = 0; i < n; i++) {
            model.defense[i] = in.readDouble();
        }
        model.homeAdv = in.readDouble();
        model.rho = in.readDouble();
        for (int i = 0; i < n; i++) {
            model.teamNames.add(in.readUTF());
        }
        return model;
    }

    // ------------------------------------------------------------------ //
    //  JSON serialisation (HDFS model file, downstream contract)         //
    // ------------------------------------------------------------------ //

    public String toJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("modelType", "DixonColes");
        root.put("version", 1);
        root.put("scoreConvention", "including-extra-time");
        root.put("nTeams", nTeams);
        root.put("homeAdv", homeAdv);
        root.put("rho", rho);
        root.put("refEpochDay", refEpochDay);
        if (!Double.isNaN(halfLifeDays)) {
            root.put("halfLifeDays", halfLifeDays);
        }
        if (sigma2 > 0.0) {
            root.put("sigma2", sigma2);
        }
        root.put("iterations", iterations);
        if (!Double.isNaN(finalLL)) {
            root.put("finalLL", finalLL);
        }
        if (!Double.isNaN(weightedCount)) {
            root.put("weightedCount", weightedCount);
        }
        if (trainedAt != null) {
            root.put("trainedAt", trainedAt);
        }
        ArrayNode teams = root.putArray("teams");
        for (int i = 0; i < nTeams; i++) {
            ObjectNode team = teams.addObject();
            team.put("idx", i);
            team.put("name", teamNames.get(i));
            team.put("attack", attack[i]);
            team.put("defense", defense[i]);
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    public static DCModel fromJson(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        if (!"DixonColes".equals(root.path("modelType").asText(null))) {
            throw new IOException("Not a DixonColes model document");
        }
        int n = root.path("nTeams").asInt();
        long refEpochDay = root.path("refEpochDay").asLong();
        DCModel model = new DCModel(n, refEpochDay);
        model.homeAdv = root.path("homeAdv").asDouble();
        model.rho = root.path("rho").asDouble();
        model.halfLifeDays = root.has("halfLifeDays")
                ? root.get("halfLifeDays").asDouble() : Double.NaN;
        model.sigma2 = root.has("sigma2") ? root.get("sigma2").asDouble() : 0.0;
        model.iterations = root.path("iterations").asInt(0);
        model.finalLL = root.has("finalLL") ? root.get("finalLL").asDouble() : Double.NaN;
        model.weightedCount = root.has("weightedCount")
                ? root.get("weightedCount").asDouble() : Double.NaN;
        if (root.has("trainedAt")) {
            model.trainedAt = root.get("trainedAt").asText();
        }
        JsonNode teams = root.path("teams");
        if (!teams.isArray() || teams.size() != n) {
            throw new IOException("teams array missing or size mismatch: " + teams.size());
        }
        String[] names = new String[n];
        for (JsonNode team : teams) {
            int idx = team.path("idx").asInt();
            if (idx < 0 || idx >= n) {
                throw new IOException("team idx out of range: " + idx);
            }
            names[idx] = team.path("name").asText();
            model.attack[idx] = team.path("attack").asDouble();
            model.defense[idx] = team.path("defense").asDouble();
        }
        for (String name : names) {
            if (name == null) {
                throw new IOException("incomplete teams array");
            }
            model.teamNames.add(name);
        }
        return model;
    }

    public List<String> immutableTeamNames() {
        return Collections.unmodifiableList(teamNames);
    }

    public byte[] toJsonBytes() throws IOException {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }
}
