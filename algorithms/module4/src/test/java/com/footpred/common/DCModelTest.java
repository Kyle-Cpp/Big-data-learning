package com.footpred.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

/** Round-trip serialisations and identifiability projection. */
public class DCModelTest {

    private DCModel sampleModel() {
        DCModel model = new DCModel(4, 20651L);
        model.teamNames.addAll(Arrays.asList("Albania", "Brazil", "Chile", "Denmark"));
        model.attack = new double[]{0.9, 0.5, 0.1, -0.3};
        model.defense = new double[]{-0.4, 0.0, 0.2, 0.6};
        model.homeAdv = 0.27;
        model.rho = -0.06;
        model.halfLifeDays = 730;
        model.iterations = 33;
        model.finalLL = -1234.5;
        model.weightedCount = 9000.0;
        model.trainedAt = "2026-10-12T10:00:00";
        return model;
    }

    @Test
    public void base64RoundTripPreservesEverything() throws IOException {
        DCModel model = sampleModel();
        String blob = model.toBase64();
        DCModel back = DCModel.fromBase64(blob);
        assertEquals(model.nTeams, back.nTeams);
        assertEquals(model.refEpochDay, back.refEpochDay);
        assertArrayEquals(model.attack, back.attack, 0.0);
        assertArrayEquals(model.defense, back.defense, 0.0);
        assertEquals(model.homeAdv, back.homeAdv, 0.0);
        assertEquals(model.rho, back.rho, 0.0);
        assertEquals(model.teamNames, back.teamNames);
    }

    @Test
    public void jsonRoundTripPreservesEverything() throws IOException {
        DCModel model = sampleModel();
        String json = model.toJson();
        DCModel back = DCModel.fromJson(json);
        assertArrayEquals(model.attack, back.attack, 0.0);
        assertArrayEquals(model.defense, back.defense, 0.0);
        assertEquals(model.homeAdv, back.homeAdv, 0.0);
        assertEquals(model.rho, back.rho, 0.0);
        assertEquals(model.refEpochDay, back.refEpochDay);
        assertEquals(model.teamNames, back.teamNames);
        assertEquals(model.halfLifeDays, back.halfLifeDays, 0.0);
        assertEquals(model.iterations, back.iterations);
        assertEquals(model.trainedAt, back.trainedAt);
    }

    @Test
    public void projectionCentresAndClips() {
        DCModel model = new DCModel(3, 0L);
        model.teamNames.addAll(Arrays.asList("A", "B", "C"));
        model.attack = new double[]{1.0, 2.0, 3.0}; // mean 2
        model.defense = new double[]{0.3, 0.3, 0.3}; // mean 0.3
        model.homeAdv = 0.25;
        model.rho = 0.5; // above RHO_MAX
        model.projectAndClip();
        assertArrayEquals(new double[]{-1.0, 0.0, 1.0}, model.attack, 1e-12);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, model.defense, 1e-12);
        assertEquals(DCModel.RHO_MAX, model.rho, 0.0);
        model.rho = -0.9;
        model.projectAndClip();
        assertEquals(DCModel.RHO_MIN, model.rho, 0.0);
    }

    @Test
    public void flatViewIsConsistent() {
        DCModel model = new DCModel(3, 0L);
        model.teamNames.addAll(Arrays.asList("A", "B", "C"));
        model.attack = new double[]{0.1, 0.0, -0.1};
        model.defense = new double[]{0.0, 0.05, -0.05};
        model.homeAdv = 0.2;
        model.rho = -0.04;
        double[] flat = model.toFlat();
        assertEquals(8, flat.length);
        assertEquals(0.1, flat[0], 0.0);
        assertEquals(0.05, flat[3 + 1], 0.0); // defense[1]
        assertEquals(0.2, flat[6], 0.0);
        assertEquals(-0.04, flat[7], 0.0);

        DCModel copy = new DCModel(3, 0L);
        copy.teamNames.addAll(Arrays.asList("A", "B", "C"));
        copy.setFlat(flat.clone());
        assertArrayEquals(model.attack, copy.attack, 1e-12);
        assertArrayEquals(model.defense, copy.defense, 1e-12);
        assertEquals(model.homeAdv, copy.homeAdv, 1e-12);
        assertEquals(model.rho, copy.rho, 1e-12);
    }

    @Test
    public void intensitiesFollowParameterisation() {
        DCModel model = sampleModel();
        // Brazil home vs Chile, not neutral
        double etaH = model.attack[1] + model.defense[2] + model.homeAdv;
        double etaA = model.attack[2] + model.defense[1];
        assertEquals(Math.exp(etaH), model.lambdaHome(1, 2, false), 1e-12);
        assertEquals(Math.exp(etaA), model.lambdaAway(1, 2), 1e-12);
        // neutral venue drops the home advantage
        assertEquals(Math.exp(etaH - model.homeAdv), model.lambdaHome(1, 2, true), 1e-12);
    }

    @Test(expected = IOException.class)
    public void fromJsonRejectsForeignDocument() throws IOException {
        DCModel.fromJson("{\"modelType\":\"SomethingElse\"}");
    }

    @Test
    public void jsonHandlesMissingOptionalMetadata() throws IOException {
        String json = "{\"modelType\":\"DixonColes\",\"version\":1,\"nTeams\":1,"
                + "\"homeAdv\":0.2,\"rho\":-0.05,\"refEpochDay\":100,"
                + "\"teams\":[{\"idx\":0,\"name\":\"X\",\"attack\":0.1,\"defense\":0.0}]}";
        DCModel model = DCModel.fromJson(json);
        assertNotNull(model);
        assertEquals(1, model.nTeams);
        assertEquals("X", model.teamNames.get(0));
    }
}
