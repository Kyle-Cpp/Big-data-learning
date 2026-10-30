package com.footpred.common;

/**
 * Per-match contribution to the weighted Dixon-Coles log-likelihood and its
 * exact gradient, evaluated at given (etaHome, etaAway, rho). Pure function,
 * fully unit-testable against numerical differentiation.
 *
 * With w the observation weight (time decay x friendly discount):
 *
 *   l  = w [ ln tau(x,y; lam,mu,rho) - lam + x*etaH - ln(x!)
 *                                          - mu + y*etaA - ln(y!) ]
 *
 *   dl/detaH = w (x - lam) + w (dtau/dlam) lam / tau
 *   dl/detaA = w (y - mu)  + w (dtau/dmu)  mu  / tau
 *   dl/drho  = w (dtau/drho) / tau
 *
 * The caller assembles these into the flat parameter gradient through
 *   etaH = attack[home] + defense[away] + homeAdv*(1-neutral)
 *   etaA = attack[away] + defense[home]
 */
public final class DCObjective {

    private DCObjective() {
    }

    /** Per-match log-likelihood value and gradient w.r.t. etaH, etaA, rho. */
    public static final class Contribution {
        public final double ll;
        public final double dEtaHome;
        public final double dEtaAway;
        public final double dRho;
        public final boolean tauClipped;

        public Contribution(double ll, double dEtaHome, double dEtaAway,
                            double dRho, boolean tauClipped) {
            this.ll = ll;
            this.dEtaHome = dEtaHome;
            this.dEtaAway = dEtaAway;
            this.dRho = dRho;
            this.tauClipped = tauClipped;
        }
    }

    public static Contribution evaluate(int gh, int ga, double etaH, double etaA,
                                        double rho, double w) {
        final double lambda = Math.exp(etaH);
        final double mu = Math.exp(etaA);

        DCtau.TauResult tau = DCtau.tauAndDerivatives(gh, ga, lambda, mu, rho);

        final double dEtaHome = w * (gh - lambda) + w * tau.dTauDlambda * lambda / tau.tau;
        final double dEtaAway = w * (ga - mu) + w * tau.dTauDmu * mu / tau.tau;
        final double dRho = w * tau.dTauDrho / tau.tau;

        final double ll = w * (Math.log(tau.tau) - lambda + gh * etaH - lnFact(gh)
                - mu + ga * etaA - lnFact(ga));
        return new Contribution(ll, dEtaHome, dEtaAway, dRho, tau.clipped);
    }

    /** Pre-computed ln(k!) up to MAX_TABLE; international scores stay <= 15. */
    private static final int MAX_TABLE = 24;
    private static final double[] LN_FACT = new double[MAX_TABLE + 1];

    static {
        double acc = 0.0;
        for (int k = 0; k <= MAX_TABLE; k++) {
            LN_FACT[k] = acc;
            acc += Math.log(k + 1);
        }
    }

    public static double lnFact(int k) {
        if (k <= MAX_TABLE) {
            return LN_FACT[k];
        }
        double acc = LN_FACT[MAX_TABLE];
        for (int i = MAX_TABLE + 1; i <= k; i++) {
            acc += Math.log(i);
        }
        return acc;
    }
}
