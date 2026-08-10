// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine-owned workspace aggregate progress. Preflight reservation + calibrated module
 * <strong>effort-weight</strong> slices + concurrent in-flight sum + monotonic peak.
 *
 * <p>Bar progress is <em>not</em> residual wall {@code R(t)/R0}. Inflated ETA seeds (history
 * floors) must not pin the bar at 99% while work remains. Countdown open-loop {@code R0} is
 * separate (client). Optional {@link #noteRemaining(long, long)} only annotates the snapshot.
 *
 * <p><b>Smart engine / dumb clients:</b> clients paint {@link Snapshot} — never re-sum plan ticks.
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
    private long total; // execute aggregate denominator, 0 until calibrated
    private long preflightNum; // 0..PREFLIGHT_UNITS
    private boolean executeCalibrated;
    private final Map<String, Long> moduleAdvanced = new HashMap<>();
    private final Map<String, ModuleSlice> modules = new HashMap<>();

    private int modulesComplete;
    private int modulesTotal;

    /** Optional residual annotation for wire (not used for bar %). */
    private long annotatedRemainingMs = -1;
    private long annotatedR0ms;

    private double peakFraction;
    private long peakDenominator;

    private Snapshot last = Snapshot.unknown();

    /**
     * @param remainingMs optional residual wall ms ({@code -1} unknown); not the bar driver
     * @param R0ms optional seed wall ms for clients that open-loop the clock
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
        return recompute();
    }

    public synchronized Snapshot preflightComplete() {
        preflightNum = PREFLIGHT_UNITS;
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
        return recompute();
    }

    /**
     * Record open-loop seed wall for wire annotation. Does <em>not</em> set the bar denominator —
     * call {@link #calibrate} with plan weights for that.
     */
    public synchronized Snapshot seedWall(long R0ms, int modulesTotal) {
        this.annotatedR0ms = Math.max(0, R0ms);
        this.annotatedRemainingMs = this.annotatedR0ms;
        if (modulesTotal > 0) this.modulesTotal = modulesTotal;
        return recompute();
    }

    /** Annotate residual remaining (wire only); bar stays on weight slices. */
    public synchronized Snapshot noteRemaining(long remainingMs, long R0ms) {
        this.annotatedRemainingMs = Math.max(0, remainingMs);
        if (R0ms > 0) this.annotatedR0ms = R0ms;
        return recompute();
    }

    /** @deprecated residual no longer drives the bar; use {@link #noteRemaining}. */
    @Deprecated
    public synchronized Snapshot setRemaining(long remainingMs) {
        return noteRemaining(remainingMs, annotatedR0ms);
    }

    public synchronized void modulesTotal(int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
    }

    /**
     * In-flight module plan view. {@code sliceHint} is the plan effort weight for first
     * registration.
     */
    public synchronized Snapshot moduleProgress(String key, long sliceHint, long numerator, long denominator) {
        if (key == null || key.isEmpty()) return last;
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
            return storeRaw(num, den, phaseName());
        }
        return recompute();
    }

    public synchronized Snapshot moduleComplete(String key, long lastDenominator) {
        if (key == null || key.isEmpty()) return last;
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
        Snapshot s = recompute();
        if (s.denominator() > 0) {
            last = new Snapshot(
                    s.denominator(),
                    s.denominator(),
                    100.0,
                    "done",
                    modulesComplete,
                    modulesTotal,
                    0,
                    annotatedR0ms);
        } else {
            last = new Snapshot(
                    s.numerator(), s.denominator(), 100.0, "done", modulesComplete, modulesTotal, 0, annotatedR0ms);
        }
        peakFraction = 1.0;
        peakDenominator = last.denominator();
        return last;
    }

    public synchronized Snapshot snapshot() {
        return last;
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

    private Snapshot recompute() {
        long execSum = completedBase;
        for (long v : moduleAdvanced.values()) execSum += v;
        long num;
        long den;
        if (executeCalibrated) {
            num = preflightNum + Math.min(execSum, total);
            den = PREFLIGHT_UNITS + total;
            if (den <= 0) den = PREFLIGHT_UNITS;
        } else {
            num = preflightNum;
            den = PREFLIGHT_UNITS + PROVISIONAL_EXECUTE_UNITS;
        }
        return storeRaw(num, den, phaseName());
    }

    private Snapshot storeRaw(long numerator, long denominator, String phase) {
        long num = numerator;
        long den = denominator;
        double f = den > 0 ? (double) num / (double) den : 0.0;
        // Hard rule: the bar never goes backwards. If the denominator grows mid-run
        // (late reweight), hold the peak fraction — do not drop percent. Correctness
        // comes from an accurate up-front denominator, not from sliding the fill back.
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
        last = new Snapshot(
                num,
                den,
                percent,
                phase,
                modulesComplete,
                modulesTotal,
                annotatedRemainingMs,
                annotatedR0ms);
        return last;
    }

    private String phaseName() {
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
