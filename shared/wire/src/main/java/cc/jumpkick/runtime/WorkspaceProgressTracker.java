// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

/**
 * Engine-owned workspace aggregate progress driven by remaining wall-work {@code R(t)}.
 *
 * <p><b>Model:</b> after {@link #seedWall(long, int)}, the execute band is pure remaining-work:
 *
 * <pre>
 *   completeFraction = 1 − R / R0
 *   display percent  = min(DISPLAY_CAP, 100 × completeFraction)   // until finish()
 * </pre>
 *
 * so the progress bar mirrors the countdown when both use the same {@link RemainingWork} oracle.
 *
 * <p>Preflight (lock/graph/plan) still paints a small band before {@code R0} is known. Clients must
 * render {@link Snapshot} values — never re-derive workspace % from per-module plan ticks.
 */
public final class WorkspaceProgressTracker {

    /** Reserved progress units for all preflight work (before {@link #seedWall}). */
    public static final long PREFLIGHT_UNITS = 100;

    /**
     * Provisional execute-band weight before {@link #seedWall}. Sized so a completed preflight band
     * is ~10% of the bar until we know {@code R0}.
     */
    public static final long PROVISIONAL_EXECUTE_UNITS = PREFLIGHT_UNITS * 9;

    /**
     * Execute-band resolution once seeded (weight units representing {@code R0}). Keeps numerator
     * integer-friendly for wire snapshots.
     */
    public static final long EXECUTE_UNITS = 10_000;

    /** Display clamp so the bar never paints 100% until {@link #finish()}. */
    public static final double DISPLAY_CAP = 99.0;

    /** TTY frame cadence (ms): engine progress-emit throttle and CLI live-region share it. */
    public static final long TTY_FRAME_MS = 80;

    private long preflightNum; // 0..PREFLIGHT_UNITS
    private boolean executeSeeded;
    private long R0; // seed wall ms
    private long R; // remaining wall ms
    private int modulesComplete;
    private int modulesTotal;

    /** Monotonic display peak at a stable total. */
    private double peakFraction;
    private long peakDenominator;

    private Snapshot last = Snapshot.unknown();

    /**
     * Immutable aggregate snapshot for wire / SSE / MCP / CLI.
     *
     * @param phase {@code preflight}, {@code execute}, or {@code done}
     * @param remainingMs remaining wall work {@code R(t)}; {@code -1} when unknown (preflight)
     * @param R0ms seed wall estimate; {@code 0} when not seeded
     */
    public record Snapshot(
            long numerator,
            long denominator,
            double percent,
            String phase,
            int modulesComplete,
            int modulesTotal,
            long remainingMs,
            long R0ms) {

        static Snapshot unknown() {
            return new Snapshot(0, 0, Double.NaN, "preflight", 0, 0, -1, 0);
        }

        /** True when {@link #percent} is a finite 0–100 value. */
        public boolean hasPercent() {
            return !Double.isNaN(percent);
        }
    }

    /** Drive preflight band. Stage-local {@code done}/{@code total} refine the plan stage. */
    public synchronized Snapshot preflight(String stage, int done, int total) {
        preflightNum = Math.min(PREFLIGHT_UNITS, Math.round(preflightFraction(stage, done, total) * PREFLIGHT_UNITS));
        return recompute();
    }

    /** Mark preflight complete (all PREFLIGHT_UNITS earned) without execute seed yet. */
    public synchronized Snapshot preflightComplete() {
        preflightNum = PREFLIGHT_UNITS;
        return recompute();
    }

    /**
     * Pin execute work to wall seed {@code R0ms}. Progress becomes {@code 1 − R/R0} with
     * {@code R = R0} initially. Prefer this over legacy weight calibration.
     *
     * <p>Resets monotonic peak so a prior preflight paint does not pin the execute bar above 0%.
     */
    public synchronized Snapshot seedWall(long R0ms, int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
        this.R0 = Math.max(0, R0ms);
        this.R = this.R0;
        this.executeSeeded = true;
        this.preflightNum = PREFLIGHT_UNITS;
        // Fresh execute band — do not inherit preflight peak fraction.
        this.peakFraction = 0;
        this.peakDenominator = 0;
        return recompute();
    }

    /**
     * @deprecated use {@link #seedWall(long, int)} — weight-sum calibration is replaced by wall
     *     remaining-work.
     */
    @Deprecated
    public synchronized Snapshot calibrate(long executeWeight, int modulesTotal) {
        // Treat legacy weight units as R0 wall-proxy so old call sites still paint.
        return seedWall(Math.max(0, executeWeight), modulesTotal);
    }

    /** Update modules total without reseeding. */
    public synchronized void modulesTotal(int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
    }

    /**
     * Set remaining wall ms {@code R(t)} from {@link RemainingWork#remaining()}. If remaining
     * <em>increases</em> (work harder than thought), grows {@code R0} so completed work is
     * preserved and the bar does not reverse.
     */
    public synchronized Snapshot setRemaining(long remainingMs) {
        long rem = Math.max(0, remainingMs);
        if (!executeSeeded) {
            // Late remaining without seed — treat as seed.
            return seedWall(rem, modulesTotal);
        }
        if (rem > R) {
            // Under-predicted mid-run: keep completed wall, stretch R0 around new remaining.
            long completed = Math.max(0, R0 - R);
            R0 = completed + rem;
            R = rem;
        } else {
            R = rem;
        }
        return recompute();
    }

    /**
     * @deprecated module slice model removed; use {@link RemainingWork} + {@link #setRemaining}.
     *     Kept as a no-op progress probe for transitional call sites that still push plan ticks —
     *     they should call {@link #setRemaining} instead. Returns last snapshot.
     */
    @Deprecated
    public synchronized Snapshot moduleProgress(String key, long sliceHint, long numerator, long denominator) {
        return last;
    }

    /** Count a finished module (for {@code modulesComplete} display). Does not change {@code R}. */
    public synchronized Snapshot moduleComplete(String key, long lastDenominator) {
        modulesComplete++;
        return recompute();
    }

    /** Terminal snapshot (phase {@code done}); forces 100%. */
    public synchronized Snapshot finish() {
        R = 0;
        Snapshot s = recompute();
        long den = s.denominator() > 0 ? s.denominator() : PREFLIGHT_UNITS + EXECUTE_UNITS;
        last = new Snapshot(den, den, 100.0, "done", modulesComplete, modulesTotal, 0, R0);
        peakFraction = 1.0;
        peakDenominator = den;
        return last;
    }

    public synchronized Snapshot snapshot() {
        return last;
    }

    /** Seed wall ms ({@code R0}); 0 before {@link #seedWall}. */
    public synchronized long executeTotal() {
        return R0;
    }

    public synchronized boolean calibrated() {
        return executeSeeded;
    }

    public synchronized long remainingMs() {
        return executeSeeded ? R : -1;
    }

    public synchronized long R0ms() {
        return R0;
    }

    private Snapshot recompute() {
        long num;
        long den;
        long remOut;
        long r0Out;
        if (executeSeeded) {
            // Execute band: EXECUTE_UNITS represent R0; completed = (R0−R)/R0 of that band.
            // Preflight is fully earned (already past) — paint only execute fraction so bar
            // mirrors countdown: percent ≈ 100 × (R0−R)/R0 capped at DISPLAY_CAP.
            double frac = R0 <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, 1.0 - (double) R / (double) R0));
            num = Math.round(frac * EXECUTE_UNITS);
            den = EXECUTE_UNITS;
            remOut = R;
            r0Out = R0;
        } else {
            num = preflightNum;
            den = PREFLIGHT_UNITS + PROVISIONAL_EXECUTE_UNITS;
            remOut = -1;
            r0Out = 0;
        }
        return storeRaw(num, den, phaseName(), remOut, r0Out);
    }

    private Snapshot storeRaw(long numerator, long denominator, String phase, long remainingMs, long R0ms) {
        long num = numerator;
        long den = denominator;
        double f = den > 0 ? (double) num / (double) den : 0.0;
        if (den > peakDenominator) {
            if (peakDenominator > 0 && f < peakFraction) {
                num = Math.round(peakFraction * den);
            } else {
                peakFraction = f;
            }
            peakDenominator = den;
        } else if (den > 0 && f < peakFraction) {
            num = Math.round(peakFraction * den);
        } else {
            peakFraction = f;
            peakDenominator = den;
        }
        double percent = den > 0 ? percentOf(num, den) : Double.NaN;
        // Cap display at DISPLAY_CAP until finish() forces 100.
        if (!"done".equals(phase) && !Double.isNaN(percent) && percent > DISPLAY_CAP) {
            percent = DISPLAY_CAP;
            num = Math.round(DISPLAY_CAP / 100.0 * den);
        }
        last = new Snapshot(num, den, percent, phase, modulesComplete, modulesTotal, remainingMs, R0ms);
        return last;
    }

    private String phaseName() {
        return executeSeeded ? "execute" : "preflight";
    }

    /** Map preflight stages onto 0..1 of the reserved band. */
    public static double preflightFraction(String stage, int done, int total) {
        if (stage == null) stage = "";
        return switch (stage) {
            case "checking" -> 0.08;
            case "lock" -> total > 0 && done >= total ? 0.30 : 0.20;
            case "graph" -> total > 0 && done >= total ? 0.40 : 0.35;
            case "plan" -> {
                if (total <= 0) yield 0.45;
                double within = Math.min(1.0, Math.max(0.0, (double) done / (double) total));
                yield 0.40 + 0.55 * within;
            }
            default -> 0.10;
        };
    }

    /** 0–100 one-decimal percent, or {@link Double#NaN} when denominator is non-positive. */
    public static double percentOf(long numerator, long denominator) {
        if (denominator <= 0) return Double.NaN;
        return clampPercent(100.0 * (double) numerator / (double) denominator);
    }

    /** Clamp to 0–100 and round to one decimal; {@link Double#NaN} passes through. */
    public static double clampPercent(double raw) {
        if (Double.isNaN(raw)) return raw;
        double v = raw < 0 ? 0 : Math.min(raw, 100);
        return Math.round(v * 10.0) / 10.0;
    }

    /** JSON number token for a percent: {@code null} for NaN, else integer or one-decimal. */
    public static String progressToken(double percent) {
        double p = clampPercent(percent);
        if (Double.isNaN(p)) return "null";
        if (p == Math.rint(p)) return Long.toString((long) p);
        return Double.toString(p);
    }
}
