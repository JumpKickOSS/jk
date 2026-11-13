// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import org.jspecify.annotations.Nullable;

/**
 * The estimate's uncertainty, on a log scale (JK-R0N5S). A step's price carries a relative spread
 * σ: its wall is read as lognormal around the price, {@code ln(wall / price) ~ N(0, σ²)}. The
 * build's spread {@link #build combines} the unfinished steps' spreads over what each has left, the
 * likely range is that distribution's 10th to 90th percentile around the remaining estimate, and
 * the confidence is the probability that the truth lands within ±10% of it.
 */
public final class EtaSpread {

    private EtaSpread() {}

    /** The standard normal's 90th percentile: the likely range is {@code R·e^(±Z90·σ)}. */
    public static final double Z90 = 1.2815515655446004;

    /**
     * How strongly two steps' errors go the same way until the host has learned it: cold estimates
     * on one host tend to err together (the calibration is one number for every compile), replayed
     * ones mostly do not.
     */
    public static final double CORRELATION = 0.5;

    /** The band the confidence is the probability of: the truth within ±10% of the estimate. */
    public static final double BAND = 0.10;

    /** The least spread a step is read with: run-to-run noise on an idle host. */
    public static final double MIN = 0.02;

    /** The most: a step priced from nothing known about it is still within about e^±2 of its price. */
    public static final double MAX = 1.0;

    /** A step's price borrowed from runs that ran beside other work (another frontier). */
    public static final double FRONTIER_BORROWED = 0.05;

    /** A step's price borrowed from runs of another shape under the same load. */
    public static final double SHAPE_BORROWED = 0.10;

    /** A step's price read across loads at the one load ratio every step shares. */
    public static final double LOAD_BORROWED = 0.30;

    /** A suite's price borrowed from its default profile: a floor under the profile's own tests. */
    public static final double PROFILE_BORROWED = 0.45;

    /** A suite re-priced at a count of test JVMs other than its walls ran on ({@link StepCost#scale}). */
    public static final double WORKERS_CHANGED = 0.10;

    /** A module counted down by its plan's ticks, with no step prices to carry a spread. */
    public static final double UNPRICED = 0.32;

    /**
     * Each rung's spread when this host has learned none for the kind (expected order from the
     * scorer's per-layer results, JK-626GS).
     */
    public static double defaultFor(StepCost.Rung rung) {
        return switch (rung) {
            case REPLAY -> 0.05;
            case MODULE -> 0.12;
            case PROJECT -> 0.22;
            case HOST -> 0.32;
            case CALIBRATION -> 0.45;
            case TOKEN -> 0.5;
        };
    }

    /** {@code parts} combined as independent log errors: the root of their squares, clamped. */
    public static double widen(double base, double... parts) {
        double v = base * base;
        for (double p : parts) v += p * p;
        return clamp(Math.sqrt(v));
    }

    /** {@code s} within {@link #MIN} to {@link #MAX}; a non-finite spread reads as {@link #MAX}. */
    public static double clamp(double s) {
        if (!Double.isFinite(s)) return MAX;
        return Math.max(MIN, Math.min(MAX, s));
    }

    /**
     * The build's spread from its unfinished steps: {@code spreadMs} is {@code Σ leftᵢ·σᵢ}, {@code
     * squaredMs} {@code Σ (leftᵢ·σᵢ)²}, {@code leftMs} {@code Σ leftᵢ} and {@code remainingMs} the
     * schedule of what is left. The correlated share {@code rho} adds as one error, the rest as
     * independent ones; independent errors on {@code k = leftMs / remainingMs} parallel
     * lanes do not cancel across lanes, only within one, so their relative variance is {@code
     * Σ(leftᵢσᵢ)² / (leftMs · remainingMs)}, which is the plain sum's on a serial build.
     */
    public static double build(double spreadMs, double squaredMs, double leftMs, double remainingMs, double rho) {
        if (leftMs <= 0 || remainingMs <= 0) return 0;
        double independent = squaredMs / (leftMs * Math.min(remainingMs, leftMs));
        double correlated = spreadMs / leftMs;
        double r = Math.max(0, Math.min(1, rho));
        double v = (1 - r) * independent + r * correlated * correlated;
        return Math.sqrt(Math.max(0, v));
    }

    /** The likely range's low end for {@code remainingMs} at spread {@code sigma}. */
    public static long low(long remainingMs, double sigma) {
        return Math.max(0, Math.round(remainingMs * Math.exp(-Z90 * sigma)));
    }

    /** The likely range's high end for {@code remainingMs} at spread {@code sigma}. */
    public static long high(long remainingMs, double sigma) {
        return Math.max(0, Math.round(remainingMs * Math.exp(Z90 * sigma)));
    }

    /**
     * The probability that the truth lands within ±{@link #BAND} of the estimate, at spread {@code
     * sigma}: {@code Φ(ln 1.1 / σ) − Φ(ln 0.9 / σ)}. {@code 1} for no spread.
     */
    public static double confidence(double sigma) {
        if (!(sigma > 0)) return 1;
        return Math.max(0, Math.min(1, phi(Math.log(1 + BAND) / sigma) - phi(Math.log(1 - BAND) / sigma)));
    }

    /** The standard normal CDF (Abramowitz and Stegun 7.1.26: within 1.5e-7). */
    static double phi(double x) {
        double z = Math.abs(x) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * z);
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double erf = 1 - poly * Math.exp(-z * z);
        return x >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }

    /** {@code c} as the wire carries it: two decimals within 0 to 1, {@code -1} for none. */
    public static double wire(double c) {
        if (!(c >= 0)) return -1;
        return Math.round(Math.min(1, c) * 100) / 100.0;
    }

    /** {@code c} as the wire carries it, two decimals; {@code null} (left out) when there is none ({@code c < 0}). */
    public static @Nullable String token(double c) {
        if (!(c >= 0)) return null;
        double r = Math.round(Math.min(1, c) * 100) / 100.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
