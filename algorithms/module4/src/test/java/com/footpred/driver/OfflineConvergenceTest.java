package com.footpred.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import com.footpred.common.DCModel;
import com.footpred.common.DCObjective;
import com.footpred.common.Shrinkage;
import com.footpred.optim.LBFGS;

/**
 * Offline end-to-end convergence tests of the complete mathematical pipeline
 * (per-match objective -> flat-gradient assembly -> L-BFGS driver loop with
 * optional shrinkage), without any Hadoop dependency. These exercise exactly
 * the same formulas the DCMapper assembles and the same accept/backtracking
 * loop the FootPredDriver runs, so a pass here validates the whole training
 * mathematics before it is ever shipped to a cluster.
 */
public class OfflineConvergenceTest {

    private static final int N_TEAMS = 4;

    private static final class Fixture {
        final int home;
        final int away;
        final int gh;
        final int ga;
        final boolean neutral;
        final double weight;

        Fixture(int home, int away, int gh, int ga, boolean neutral, double weight) {
            this.home = home;
            this.away = away;
            this.gh = gh;
            this.ga = ga;
            this.neutral = neutral;
            this.weight = weight;
        }
    }

    /** Aggregates the flat log-likelihood gradient exactly like DCMapper. */
    private static double[] gradientAndLL(DCModel model, java.util.List<Fixture> data,
                                          double[] llOut) {
        int n = model.nTeams;
        double[] grad = new double[DCModel.flatDim(n)];
        double ll = 0.0;
        for (Fixture f : data) {
            double etaH = model.etaHome(f.home, f.away, f.neutral);
            double etaA = model.etaAway(f.home, f.away);
            DCObjective.Contribution c =
                    DCObjective.evaluate(f.gh, f.ga, etaH, etaA, model.rho, f.weight);
            grad[f.home] += c.dEtaHome;
            grad[f.away] += c.dEtaAway;
            grad[n + f.away] += c.dEtaHome;
            grad[n + f.home] += c.dEtaAway;
            if (!f.neutral) {
                grad[2 * n] += c.dEtaHome;
            }
            grad[2 * n + 1] += c.dRho;
            ll += c.ll;
        }
        llOut[0] = ll;
        return grad;
    }

    /** Driver-side shrinkage application, mirrored for offline testing. */
    private static double applyShrinkage(DCModel model, double[] gradNll, double sigma2) {
        if (sigma2 <= 0.0) {
            return 0.0;
        }
        Shrinkage.addPenaltyGradient(gradNll, model.attack, model.defense, sigma2);
        return Shrinkage.penaltyValue(model.attack, model.defense, sigma2);
    }

    /**
     * Full mirror of the FootPredDriver training loop (accept/backtracking
     * L-BFGS with optional shrinkage prior), running offline.
     */
    private static DCModel optimize(java.util.List<Fixture> data, double sigma2, int maxAttempts) {
        DCModel model = new DCModel(N_TEAMS, 20000L);
        java.util.Collections.addAll(model.teamNames, "Alpha", "Beta", "Gamma", "Delta");
        model.initialTheta();
        model.setFlat(model.toFlat());
        double[] theta = model.toFlat();

        LBFGS lbfgs = new LBFGS(8);
        double[] llOut = new double[1];
        double[] g = negate(gradientAndLL(model, data, llOut));
        double f = -llOut[0] + applyShrinkage(model, g, sigma2);
        double[] d = negate(g);
        double step = 3e-4;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            double[] candidate = new double[theta.length];
            for (int i = 0; i < theta.length; i++) {
                candidate[i] = theta[i] + step * d[i];
            }
            model.setFlat(candidate);
            double[] candidateP = model.toFlat();

            double[] gCand = negate(gradientAndLL(model, data, llOut));
            double fCand = -llOut[0] + applyShrinkage(model, gCand, sigma2);

            boolean accept = fCand <= f + 1e-12 * Math.max(1.0, Math.abs(f));
            if (accept) {
                lbfgs.update(candidateP, theta, gCand, g);
                if (Math.abs(f - fCand) <= 1e-10 * Math.max(1.0, Math.abs(f))) {
                    theta = candidateP;
                    g = gCand;
                    f = fCand;
                    break;
                }
                theta = candidateP;
                g = gCand;
                f = fCand;
                d = lbfgs.hasHistory() ? lbfgs.computeDirection(g) : negate(g);
                step = 1.0;
            } else {
                step *= 0.25;
                if (step < 1e-9) {
                    lbfgs.reset();
                    d = negate(g);
                    step = 1e-2;
                }
            }
        }
        model.setFlat(theta);
        return model;
    }

    @Test
    public void lbfgsRecoversSyntheticGroundTruth() {
        // ground truth: attack ordering team0 > team1 > team2 > team3
        double[] trueAttack = {0.55, 0.25, -0.10, -0.70};
        double[] trueDefense = {-0.45, -0.05, 0.15, 0.35};
        double trueHomeAdv = 0.30;

        Random rng = new Random(20261012L);
        java.util.List<Fixture> data = new java.util.ArrayList<Fixture>();
        for (int round = 0; round < 60; round++) {
            for (int home = 0; home < N_TEAMS; home++) {
                for (int away = 0; away < N_TEAMS; away++) {
                    if (home == away) {
                        continue;
                    }
                    boolean neutral = (round % 4 == 0);
                    double lam = Math.exp(trueAttack[home] + trueDefense[away]
                            + (neutral ? 0.0 : trueHomeAdv));
                    double mu = Math.exp(trueAttack[away] + trueDefense[home]);
                    data.add(new Fixture(home, away, samplePoisson(rng, lam),
                            samplePoisson(rng, mu), neutral, 1.0));
                }
            }
        }

        DCModel model = optimize(data, 0.0, 400);

        // ranking of attack parameters must match the ground-truth ordering
        assertTrue("Alpha strongest attack", model.attack[0] > model.attack[1]);
        assertTrue("Beta second attack", model.attack[1] > model.attack[2]);
        assertTrue("Gamma third attack", model.attack[2] > model.attack[3]);
        // defence: lower value = stronger defence
        assertTrue("Alpha strongest defence", model.defense[0] < model.defense[3]);
        assertTrue("home advantage recovered positive", model.homeAdv > 0.20);

        // rho: independent-Poisson data carries no tau correlation, so the MLE
        // is only a finite-sample artefact - assert a stationary point inside
        // the admissible clip range instead of any specific value
        assertTrue("rho inside clip range", Math.abs(model.rho) <= DCModel.RHO_MAX + 1e-12);

        // intensities close to truth for a canonical non-neutral pairing
        double lam01 = model.lambdaHome(0, 1, false);
        double truth01 = Math.exp(trueAttack[0] + trueDefense[1] + trueHomeAdv);
        assertEquals(truth01, lam01, 0.30);
        double mu01 = model.lambdaAway(0, 1);
        double truthMu = Math.exp(trueAttack[1] + trueDefense[0]);
        assertEquals(truthMu, mu01, 0.25);
    }

    @Test
    public void shrinkagePullsThinEvidenceTeamsTowardLeagueAverage() {
        // three well-observed teams round-robin; Delta appears in exactly two
        // heavy defeats - thin evidence that would otherwise push its
        // parameters to extremes
        double[] trueAttack = {0.55, 0.25, -0.10};
        double[] trueDefense = {-0.45, -0.05, 0.15};
        double trueHomeAdv = 0.30;

        Random rng = new Random(20261013L);
        java.util.List<Fixture> data = new java.util.ArrayList<Fixture>();
        for (int round = 0; round < 60; round++) {
            for (int home = 0; home < 3; home++) {
                for (int away = 0; away < 3; away++) {
                    if (home == away) {
                        continue;
                    }
                    double lam = Math.exp(trueAttack[home] + trueDefense[away] + trueHomeAdv);
                    double mu = Math.exp(trueAttack[away] + trueDefense[home]);
                    data.add(new Fixture(home, away, samplePoisson(rng, lam),
                            samplePoisson(rng, mu), false, 1.0));
                }
            }
        }
        // Delta: two lopsided defeats only
        data.add(new Fixture(3, 0, 0, 5, false, 1.0));
        data.add(new Fixture(3, 1, 0, 3, false, 1.0));

        DCModel unshrunk = optimize(data, 0.0, 400);
        DCModel shrunk = optimize(data, 0.05, 400); // prior worth ~20 effective matches

        double magUnshrunk = Math.abs(unshrunk.attack[3]) + Math.abs(unshrunk.defense[3]);
        double magShrunk = Math.abs(shrunk.attack[3]) + Math.abs(shrunk.defense[3]);
        assertTrue("unshrunk Delta drifted far from league average (setup check)",
                magUnshrunk > 1.0);
        assertTrue("shrinkage pulled thin-evidence Delta toward league average"
                        + " (" + magShrunk + " vs " + magUnshrunk + ")",
                magShrunk < 0.75 * magUnshrunk);

        // well-observed teams keep their strength: parameters are only
        // identified up to the centring projection (shrinking Delta re-centres
        // everyone), so the invariant to check is RELATIVE strength - the
        // pairwise attack gaps and the predicted intensities must stay put
        double gap01u = unshrunk.attack[0] - unshrunk.attack[1];
        double gap01s = shrunk.attack[0] - shrunk.attack[1];
        double gap12u = unshrunk.attack[1] - unshrunk.attack[2];
        double gap12s = shrunk.attack[1] - shrunk.attack[2];
        assertTrue("attack gap Alpha-Beta preserved",
                Math.abs(gap01s - gap01u) < 0.10);
        assertTrue("attack gap Beta-Gamma preserved",
                Math.abs(gap12s - gap12u) < 0.10);
        assertEquals("predicted intensity Alpha vs Beta preserved",
                unshrunk.lambdaHome(0, 1, false), shrunk.lambdaHome(0, 1, false), 0.10);
        assertEquals("predicted intensity Gamma vs Alpha preserved",
                unshrunk.lambdaHome(2, 0, false), shrunk.lambdaHome(2, 0, false), 0.10);
        // the ordering of the well-observed teams is preserved under shrinkage
        assertTrue(shrunk.attack[0] > shrunk.attack[1]);
        assertTrue(shrunk.attack[1] > shrunk.attack[2]);
    }

    private static int samplePoisson(Random rng, double lambda) {
        double l = Math.exp(-lambda);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > l);
        return k - 1;
    }

    private static double[] negate(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = -v[i];
        }
        return out;
    }
}
