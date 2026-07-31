// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine-owned workspace aggregate progress. Preflight reservation + calibrated module
 * weight slices + concurrent in-flight sum + monotonic peak.
 *
 * <p><b>Smart engine / dumb clients:</b> all aggregate tuning lives here (or in call sites that only
 * feed this tracker). Wire, SSE, MCP, CLI, and session JSONL must render {@link Snapshot} values
 * never re-derive workspace % from per-module pipeline ticks.
 */
public final class WorkspaceProgressTracker {

    /** Reserved progress units for all preflight work (before module pipelines run). */
    public static final long PREFLIGHT_UNITS = 100;

    /**
     * Provisional execute-band weight used <em>before</em> {@link #calibrate}. Without this, the
     * preflight-only denominator is {@link #PREFLIGHT_UNITS} alone, so lock/graph/plan paint the bar
     * nearly full and then snap back when real execute weight is pinned — a first-build flash.
     * Sized so a completed preflight band is ~10% of the bar until we know better.
     */
    public static final long PROVISIONAL_EXECUTE_UNITS = PREFLIGHT_UNITS * 9;

    /** TTY frame cadence (ms): engine progress-emit throttle and CLI live-region share it. */
    public static final long TTY_FRAME_MS = 80;

    private long completedBase;
    private long total; // execute aggregate denominator, 0 until calibrated
    private long preflightNum; // 0..PREFLIGHT_UNITS
    private boolean executeCalibrated;
    private final Map<String, Long> moduleAdvanced = new HashMap<>();
    private final Map<String, ModuleSlice> modules = new HashMap<>();

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
     */
    public record Snapshot(
            long numerator, long denominator, double percent, String phase, int modulesComplete, int modulesTotal) {

        static Snapshot unknown() {
            return new Snapshot(0, 0, Double.NaN, "preflight", 0, 0);
        }

        /** True when {@link #percent} is a finite 0–100 value. */
        public boolean hasPercent() {
            return !Double.isNaN(percent);
        }
    }

    private static final class ModuleSlice {
        long slice;
        long knownDenominator;
    }

    /** Drive preflight band. Stage-local {@code done}/{@code total} refine the plan stage. */
    public synchronized Snapshot preflight(String stage, int done, int total) {
        preflightNum = Math.min(PREFLIGHT_UNITS, Math.round(preflightFraction(stage, done, total) * PREFLIGHT_UNITS));
        return recompute();
    }

    /** Mark preflight complete (all PREFLIGHT_UNITS earned) without execute calibrate yet. */
    public synchronized Snapshot preflightComplete() {
        preflightNum = PREFLIGHT_UNITS;
        return recompute();
    }

    /**
     * Pin execute weight after plan. {@code executeWeight} is Σ module pipeline weights (ticks).
     * {@code modulesTotal} is the planned module count (0 if unknown).
     *
     * <p>/1154: when modules are planned but execute weight is still 0 (every step token
     * not yet applied, or a bug), floor the total at {@code modulesTotal} so calibrate never
     * leaves an empty execute band that falls into per-module uncalibrated math (which looks
     * like a count reset when modules swap).
     */
    public synchronized Snapshot calibrate(long executeWeight, int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
        long w = Math.max(0, executeWeight);
        if (w == 0 && this.modulesTotal > 0) w = this.modulesTotal; // ≥1 token per module
        this.total = w;
        this.executeCalibrated = true;
        this.preflightNum = PREFLIGHT_UNITS;
        return recompute();
    }

    /** Update modules total without recalibrating weights (plan count only). */
    public synchronized void modulesTotal(int modulesTotal) {
        this.modulesTotal = Math.max(0, modulesTotal);
    }

    /**
     * In-flight (or starting) module pipeline view. {@code sliceHint} is the plan weight for first
     * registration; later reweights follow {@code denominator} deltas when calibrated.
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
                // Reweight with the denominator; clamp a shrink so slice/total never go negative.
                long delta = Math.max(denominator - m.knownDenominator, -m.slice);
                total = Math.max(0, total + delta);
                m.slice += delta;
                m.knownDenominator = denominator;
            }
            double frac = denominator > 0 ? (double) numerator / (double) denominator : 0.0;
            if (frac < 0) frac = 0;
            if (frac > 1) frac = 1;
            long advanced = Math.round(frac * m.slice);
            moduleAdvanced.put(key, advanced);
        } else {
            // Uncalibrated: live ticks against a growing denominator.
            long base = completedBase;
            long num = base + Math.max(0, numerator);
            long den = base + Math.max(0, denominator);
            return storeRaw(num, den, phaseName());
        }
        return recompute();
    }

    /**
     * Module finished. Calibrated: add reserved slice to base and drop running contribution.
     * Uncalibrated: fold {@code lastDenominator} into the completed base.
     */
    public synchronized Snapshot moduleComplete(String key, long lastDenominator) {
        if (key == null || key.isEmpty()) return last;
        ModuleSlice m = modules.remove(key);
        moduleAdvanced.remove(key);
        if (executeCalibrated && total > 0) {
            long slice = m != null ? m.slice : 0;
            completedBase += slice;
        } else {
            completedBase += Math.max(0, lastDenominator);
        }
        modulesComplete++;
        return recompute();
    }

    /** Terminal snapshot (phase {@code done}); forces 100% when denominator is known. */
    public synchronized Snapshot finish() {
        Snapshot s = recompute();
        if (s.denominator() > 0) {
            last = new Snapshot(s.denominator(), s.denominator(), 100.0, "done", modulesComplete, modulesTotal);
        } else {
            last = new Snapshot(s.numerator(), s.denominator(), 100.0, "done", modulesComplete, modulesTotal);
        }
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
            // Preflight is a small early slice of a provisional full bar — never 0–100% of
            // PREFLIGHT_UNITS alone (that is the flash: full bar, then snap-back at calibrate).
            num = preflightNum;
            den = PREFLIGHT_UNITS + PROVISIONAL_EXECUTE_UNITS;
        }
        return storeRaw(num, den, phaseName());
    }

    private Snapshot storeRaw(long numerator, long denominator, String phase) {
        long num = numerator;
        long den = denominator;
        // Monotonic peak: at a stable total, never slide backward. When the total grows
        // (provisional → calibrated execute weight), keep the displayed *fraction* from dropping
        // so preflight→calibrate does not flash the bar back toward zero.
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
        last = new Snapshot(num, den, percent, phase, modulesComplete, modulesTotal);
        return last;
    }

    private String phaseName() {
        // "done" is finish's alone — everything after calibrate is "execute".
        return executeCalibrated ? "execute" : "preflight";
    }

    /** Map preflight stages onto 0..1 of the reserved band (same mapping as former CLI). */
    public static double preflightFraction(String stage, int done, int total) {
        if (stage == null) stage = "";
        return switch (stage) {
            case "checking" -> 0.08;
            case "lock" -> total > 0 && done >= total ? 0.30 : 0.20;
            case "graph" -> total > 0 && done >= total ? 0.40 : 0.35;
            case "plan" -> {
                if (total <= 0) yield 0.45;
                double within = Math.min(1.0, Math.max(0.0, (double) done / (double) total));
                // Cap below 1.0: before calibrate the denominator is the preflight band
                // alone, and a plan-complete 100/100 snapshot pins every peak-holding
                // consumer at 100% for the whole execute phase.
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
