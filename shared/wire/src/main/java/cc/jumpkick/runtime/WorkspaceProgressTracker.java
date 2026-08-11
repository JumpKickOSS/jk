// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.runtime.progress.ClockProgressStrategy;
import cc.jumpkick.runtime.progress.HeaderProgressState;
import cc.jumpkick.runtime.progress.HeaderProgressStrategy;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import cc.jumpkick.runtime.progress.WeightedProgressStrategy;
import java.util.HashMap;
import java.util.Map;

/**
 * Engine-owned workspace aggregate progress. Effort-weight slices are always tracked for wire
 * numerator/denominator; <strong>percent</strong> is painted by {@link ProgressBarMode} (default
 * AUTO: open-loop clock when R0 &gt; 0, else weighted).
 *
 * <p>Bar never goes backwards (peak hold inside strategies). Clients may recompute clock percent
 * between events using R0 + elapsed (web SPA does this).
 */
public final class WorkspaceProgressTracker {

    /** Reserved progress units for all preflight work (before module plans run). */
    public static final long PREFLIGHT_UNITS = 100;

    /**
     * Provisional execute-band weight before {@link #calibrate}. Sized so completed preflight is
     * ~10% of the bar until execute weight is known.
     */
    public static final long PROVISIONAL_EXECUTE_UNITS = PREFLIGHT_UNITS * 9;

    /** TTY frame cadence (ms). */
    public static final long TTY_FRAME_MS = 80;

    private long completedBase;
    private long total; // execute aggregate weight denominator, 0 until calibrated
    private long preflightNum; // 0..PREFLIGHT_UNITS
    private boolean executeCalibrated;
    private final Map<String, Long> moduleAdvanced = new HashMap<>();
    private final Map<String, ModuleSlice> modules = new HashMap<>();

    private int modulesComplete;
    private int modulesTotal;

    private long annotatedRemainingMs = -1;
    private long annotatedR0ms;
    /** Elapsed base when R0 was seeded (millis, same clock as {@link #elapsedClockMs}). */
    private long seedAtElapsedMs;

    private long elapsedClockMs;
    private boolean settled;

    private final ProgressBarMode mode;

    public WorkspaceProgressTracker() {
        this(ProgressBarMode.fromEnvironment());
    }

    /** Per-request mode from the wire (JK-1816); falls back to the process env when absent. */
    public WorkspaceProgressTracker(ProgressBarMode mode) {
        this.mode = mode == null ? ProgressBarMode.fromEnvironment() : mode;
    }
    /** One monotonic floor across the strategy pair — the AUTO takeover must not repaint backwards. */
    private final cc.jumpkick.runtime.progress.SharedPeak displayedPeak = new cc.jumpkick.runtime.progress.SharedPeak();

    private final ClockProgressStrategy clock = new ClockProgressStrategy(displayedPeak);
    private final WeightedProgressStrategy weighted = new WeightedProgressStrategy(displayedPeak);

    private Snapshot last = Snapshot.unknown();

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

        public boolean hasPercent() {
            return !Double.isNaN(percent);
        }
    }

    private static final class ModuleSlice {
        long slice;
        long knownDenominator;
    }

    public synchronized Snapshot preflight(String stage, int done, int total) {
        preflightNum = Math.min(PREFLIGHT_UNITS, Math.round(preflightFraction(stage, done, total) * PREFLIGHT_UNITS));
        tickElapsed();
        return recompute();
    }

    public synchronized Snapshot preflightComplete() {
        preflightNum = PREFLIGHT_UNITS;
        tickElapsed();
        return recompute();
    }

    /**
     * Pin execute weight after plan. {@code executeWeight} is Σ module plan effort weights (not
     * wall-ms R0).
     */
    public synchronized Snapshot calibrate(long executeWeight, int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
        long w = Math.max(0, executeWeight);
        if (w == 0 && this.modulesTotal > 0) w = this.modulesTotal;
        this.total = w;
        this.executeCalibrated = true;
        this.preflightNum = PREFLIGHT_UNITS;
        tickElapsed();
        return recompute();
    }

    /**
     * Record open-loop seed wall R0. Starts the clock base for {@link ClockProgressStrategy} when
     * R0 &gt; 0.
     */
    public synchronized Snapshot seedWall(long R0ms, int modulesTotal) {
        long r0 = Math.max(0, R0ms);
        this.annotatedR0ms = r0;
        this.annotatedRemainingMs = r0;
        if (modulesTotal > 0) this.modulesTotal = modulesTotal;
        tickElapsed();
        // First seed bases at the current tracker-clock elapsed (0 when seeded immediately).
        if (r0 > 0 && seedAtElapsedMs == 0) seedAtElapsedMs = elapsedClockMs;
        return recompute();
    }

    /** Annotate residual remaining (wire only for countdown clients). */
    public synchronized Snapshot noteRemaining(long remainingMs, long R0ms) {
        this.annotatedRemainingMs = Math.max(0, remainingMs);
        if (R0ms > 0) {
            if (this.annotatedR0ms <= 0) {
                // First R0 via residual path — treat as seed.
                this.annotatedR0ms = R0ms;
                tickElapsed();
                seedAtElapsedMs = elapsedClockMs;
            } else {
                this.annotatedR0ms = R0ms;
            }
        }
        tickElapsed();
        return recompute();
    }

    @Deprecated
    public synchronized Snapshot setRemaining(long remainingMs) {
        return noteRemaining(remainingMs, annotatedR0ms);
    }

    public synchronized void modulesTotal(int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
    }

    public synchronized Snapshot moduleProgress(String key, long sliceHint, long numerator, long denominator) {
        if (key == null || key.isEmpty()) return last;
        tickElapsed();
        if (executeCalibrated && total > 0) {
            ModuleSlice m = modules.get(key);
            if (m == null) {
                m = new ModuleSlice();
                m.slice = Math.max(0, sliceHint);
                m.knownDenominator = denominator > 0 ? denominator : m.slice;
                modules.put(key, m);
            } else if (denominator != m.knownDenominator) {
                long delta = Math.max(denominator - m.knownDenominator, -m.slice);
                total = Math.max(0, total + delta);
                m.slice += delta;
                m.knownDenominator = denominator;
            }
            double frac = denominator > 0 ? (double) numerator / (double) denominator : 0.0;
            if (frac < 0) frac = 0;
            if (frac > 1) frac = 1;
            moduleAdvanced.put(key, Math.round(frac * m.slice));
        } else {
            long base = completedBase;
            long num = base + Math.max(0, numerator);
            long den = base + Math.max(0, denominator);
            return storeWeightAndStrategy(num, den, phaseName());
        }
        return recompute();
    }

    public synchronized Snapshot moduleComplete(String key, long lastDenominator) {
        if (key == null || key.isEmpty()) return last;
        tickElapsed();
        ModuleSlice m = modules.remove(key);
        moduleAdvanced.remove(key);
        if (executeCalibrated && total > 0) {
            completedBase += m != null ? m.slice : 0;
        } else {
            completedBase += Math.max(0, lastDenominator);
        }
        modulesComplete++;
        return recompute();
    }

    public synchronized Snapshot finish() {
        settled = true;
        tickElapsed();
        Snapshot s = recompute();
        last = new Snapshot(
                s.numerator(), s.denominator(), 100.0, "done", modulesComplete, modulesTotal, 0, annotatedR0ms);
        return last;
    }

    public synchronized Snapshot snapshot() {
        // Recompute so open-loop percent advances between module events when callers poll.
        tickElapsed();
        return recompute();
    }

    public synchronized long executeTotal() {
        return total;
    }

    public synchronized boolean calibrated() {
        return executeCalibrated;
    }

    public synchronized long remainingMs() {
        return annotatedRemainingMs;
    }

    public synchronized long R0ms() {
        return annotatedR0ms;
    }

    /** Active strategy id for tests ({@code clock} / {@code weighted}). */
    public synchronized String progressStrategyId() {
        return mode.select(clock, weighted, annotatedR0ms, annotatedRemainingMs).id();
    }

    private void tickElapsed() {
        // Monotonic synthetic clock via a System.nanoTime base — wall clock jumps (NTP steps)
        // inflated or froze elapsed when this used currentTimeMillis (JK-1829). First call
        // establishes the epoch.
        long nowNanos = System.nanoTime();
        if (elapsedEpochNanos == 0) {
            elapsedEpochNanos = nowNanos;
            elapsedClockMs = 0;
        } else {
            elapsedClockMs = Math.max(elapsedClockMs, (nowNanos - elapsedEpochNanos) / 1_000_000L);
        }
    }

    private long elapsedEpochNanos;

    private Snapshot recompute() {
        long execSum = completedBase;
        for (long v : moduleAdvanced.values()) execSum += v;
        long weightNum;
        long weightDen;
        if (executeCalibrated) {
            weightNum = preflightNum + Math.min(execSum, total);
            weightDen = PREFLIGHT_UNITS + total;
            if (weightDen <= 0) weightDen = PREFLIGHT_UNITS;
        } else {
            weightNum = preflightNum;
            weightDen = PREFLIGHT_UNITS + PROVISIONAL_EXECUTE_UNITS;
        }
        return storeWeightAndStrategy(weightNum, weightDen, phaseName());
    }

    private Snapshot storeWeightAndStrategy(long weightNum, long weightDen, String phase) {
        HeaderProgressStrategy strat = mode.select(clock, weighted, annotatedR0ms, annotatedRemainingMs);
        // residualRemainingMs: private bar estimate (RemainingWork); -1 until first noteRemaining.
        long residual = annotatedRemainingMs; // already -1 or >=0
        // After seed, remaining was set to R0 — treat as residual only once work has progressed
        // or noteRemaining refreshed it (same field). Always pass it: elapsed/(elapsed+R) at t=0
        // is 0, matching open-loop.
        HeaderProgressState st = new HeaderProgressState(
                weightNum, weightDen, annotatedR0ms, seedAtElapsedMs, elapsedClockMs, residual, settled);
        // Feed weights so weighted strategy peak tracks; clock uses residual + elapsed.
        long[] d = strat.onWeightProgress(st, weightNum, weightDen);
        // Prefer strategy display pair for percent; keep weight units on the wire for den/num
        // when weighted, or synthetic scale when clock.
        long num = d[0];
        long den = d[1];
        if (den <= 0) {
            num = weightNum;
            den = weightDen;
        }
        double percent = den > 0 ? percentOf(num, den) : Double.NaN;
        if (settled) percent = 100.0;
        last = new Snapshot(
                num, den, percent, phase, modulesComplete, modulesTotal, annotatedRemainingMs, annotatedR0ms);
        return last;
    }

    private String phaseName() {
        if (settled) return "done";
        return executeCalibrated ? "execute" : "preflight";
    }

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

    public static double percentOf(long numerator, long denominator) {
        if (denominator <= 0) return Double.NaN;
        return clampPercent(100.0 * (double) numerator / (double) denominator);
    }

    public static double clampPercent(double raw) {
        if (Double.isNaN(raw)) return raw;
        double v = raw < 0 ? 0 : Math.min(raw, 100);
        return Math.round(v * 10.0) / 10.0;
    }

    public static String progressToken(double percent) {
        double p = clampPercent(percent);
        if (Double.isNaN(p)) return "null";
        if (p == Math.rint(p)) return Long.toString((long) p);
        return Double.toString(p);
    }
}
