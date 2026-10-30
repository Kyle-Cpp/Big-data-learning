package com.footpred.common;

/**
 * Dixon-Coles low-score correction tau(x, y; lambda, mu, rho) with exact
 * partial derivatives, per Dixon &amp; Coles (1997), equations (2.2)-(2.5):
 *
 *   tau(0,0) = 1 - lambda*mu*rho
 *   tau(0,1) = 1 + lambda*rho
 *   tau(1,0) = 1 + mu*rho
 *   tau(1,1) = 1 - rho
 *   tau      = 1 otherwise
 *
 * where lambda is the home expectation and mu the away expectation.
 * Derivatives follow by direct differentiation of each case.
 *
 * Numerical guard: if tau drops to the floor the log-likelihood contribution
 * is frozen and the derivatives are zeroed, so a step that would push rho
 * outside the admissible region for some extreme lambda*mu pair never
 * produces NaN/Inf gradients. Callers must count clipped cases (counter
 * "dc.bad_tau") so this stays observable during optimisation.
 */
public final class DCtau {

    public static final double TAU_FLOOR = 1e-10;

    private DCtau() {
    }

    /** tau and its partial derivatives w.r.t. lambda, mu, rho. */
    public static final class TauResult {
        public double tau;
        public double dTauDlambda;
        public double dTauDmu;
        public double dTauDrho;
        public boolean clipped;

        public TauResult(double tau, double dLambda, double dMu, double dRho, boolean clipped) {
            this.tau = tau;
            this.dTauDlambda = dLambda;
            this.dTauDmu = dMu;
            this.dTauDrho = dRho;
            this.clipped = clipped;
        }
    }

    /** tau value only (no derivatives, no allocation) - for score-matrix jobs. */
    public static double tau(int x, int y, double lambda, double mu, double rho) {
        if (x == 0 && y == 0) {
            return Math.max(TAU_FLOOR, 1.0 - lambda * mu * rho);
        }
        if (x == 0 && y == 1) {
            return Math.max(TAU_FLOOR, 1.0 + lambda * rho);
        }
        if (x == 1 && y == 0) {
            return Math.max(TAU_FLOOR, 1.0 + mu * rho);
        }
        if (x == 1 && y == 1) {
            return Math.max(TAU_FLOOR, 1.0 - rho);
        }
        return 1.0;
    }

    public static TauResult tauAndDerivatives(int x, int y, double lambda, double mu, double rho) {
        double tau;
        double dLambda;
        double dMu;
        double dRho;
        if (x == 0 && y == 0) {
            tau = 1.0 - lambda * mu * rho;
            dLambda = -mu * rho;
            dMu = -lambda * rho;
            dRho = -lambda * mu;
        } else if (x == 0 && y == 1) {
            tau = 1.0 + lambda * rho;
            dLambda = rho;
            dMu = 0.0;
            dRho = lambda;
        } else if (x == 1 && y == 0) {
            tau = 1.0 + mu * rho;
            dLambda = 0.0;
            dMu = rho;
            dRho = mu;
        } else if (x == 1 && y == 1) {
            tau = 1.0 - rho;
            dLambda = 0.0;
            dMu = 0.0;
            dRho = -1.0;
        } else {
            tau = 1.0;
            dLambda = 0.0;
            dMu = 0.0;
            dRho = 0.0;
        }
        if (tau <= TAU_FLOOR) {
            return new TauResult(TAU_FLOOR, 0.0, 0.0, 0.0, true);
        }
        return new TauResult(tau, dLambda, dMu, dRho, false);
    }
}
