// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.runtime.progress.ClockProgressStrategy;
import cc.jumpkick.wire.runtime.progress.HeaderProgressState;
import cc.jumpkick.wire.runtime.progress.HeaderProgressStrategy;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import cc.jumpkick.wire.runtime.progress.SharedPeak;
import cc.jumpkick.wire.runtime.progress.WeightedProgressStrategy;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

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

    /** Elapsed ms when {@link #annotatedRemainingMs} last changed: the bar counts it down from there. */
    private long remainingAtElapsedMs;

    private long annotatedR0ms;
    /** Elapsed base when R0 was seeded (millis, same clock as {@link #elapsedClockMs}). */
    private long seedAtElapsedMs;
    /** Elapsed ms when execute was calibrated ({@code -1} before): where a late seed's clock starts. */
    private long executeStartElapsedMs = -1;

    private long elapsedClockMs;
    private boolean settled;

    private final ProgressBarMode mode;
    private final Clock ticker;

    public WorkspaceProgressTracker() {
        this(ProgressBarMode.fromEnvironment());
    }

    /** Per-request mode from the wire; falls back to the process env when absent. */
    public WorkspaceProgressTracker(@Nullable ProgressBarMode mode) {
        this(mode, Clock.SYSTEM);
    }

    /** As above, with the ticker the elapsed clock reads; tests pass a fake and advance it. */
    public WorkspaceProgressTracker(@Nullable ProgressBarMode mode, Clock ticker) {
        this.mode = mode == null ? ProgressBarMode.fromEnvironment() : mode;
        this.ticker = ticker;
    }
    /** One monotonic floor across the strategy pair — the AUTO takeover must not repaint backwards. */
    private final SharedPeak displayedPeak = new SharedPeak();

    private final ClockProgressStrategy clock = new ClockProgressStrategy(displayedPeak);
    private final WeightedProgressStrategy weighted = new WeightedProgressStrategy(displayedPeak);

    private Snapshot last = Snapshot.unknown();

    /**
     * What the bar shows now. {@code lowMs} and {@code highMs} bound {@code remainingMs}: the
     * estimate's likely range at the same anchor, {@code -1} when there is none (JK-4TEZ7).
     */
    public record Snapshot(
            long numerator,
            long denominator,
            double percent,
            String phase,
            int modulesComplete,
            int modulesTotal,
            long remainingMs,
            long R0ms,
            long lowMs,
            long highMs) {

        public Snapshot(
                long numerator,
                long denominator,
                double percent,
                String phase,
                int modulesComplete,
                int modulesTotal,
                long remainingMs,
                long R0ms) {
            this(numerator, denominator, percent, phase, modulesComplete, modulesTotal, remainingMs, R0ms, -1, -1);
        }

        static Snapshot unknown() {
            return new Snapshot(0, 0, Double.NaN, "preflight", 0, 0, -1, 0);
        }

        /** Whether the estimate carries a range. */
        public boolean hasRange() {
            return remainingMs >= 0 && lowMs >= 0 && highMs >= 0;
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
        if (executeStartElapsedMs < 0) executeStartElapsedMs = elapsedClockMs;
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
        // The work model prices the scheduled modules that do work, which can be fewer than the
        // plan schedules (a dependent forecast as a cache hit still runs and completes): never
        // lower the plan's count, or pending reads 0 while modules still run and the bar's
        // residual counts down mid-build.
        if (modulesTotal > 0) this.modulesTotal = Math.max(this.modulesTotal, modulesTotal);
        tickElapsed();
        // Every accepted seed re-anchors the clock base — matching the CLI, whose pre-execute
        // re-seed always re-anchors (setRemainingWorkEstimate). The old `seedAtElapsedMs == 0`
        // guard conflated "never seeded" with "seeded at elapsed 0": a provisional → refined
        // re-seed kept the stale anchor iff the first seed landed later than tracker-elapsed 0,
        // and SSE percent silently diverged from the TUI bar.
        if (r0 > 0) seedAtElapsedMs = seedAnchor();
        // The seed is the first residual, taken where the clock counts from (as the CLI does).
        remainingAtElapsedMs = seedAtElapsedMs;
        return recompute();
    }

    /**
     * Where the clock counts from: execute's start once it began, else now. The ETA is priced beside
     * the build, so its seed can arrive seconds into execute; anchored at its arrival, the clock
     * read 0% at takeover, the floor held the bar flat, and it then counted only the time after.
     */
    private long seedAnchor() {
        return executeStartElapsedMs >= 0 ? Math.min(elapsedClockMs, executeStartElapsedMs) : elapsedClockMs;
    }

    /** Annotate residual remaining (wire only for countdown clients). */
    public synchronized Snapshot noteRemaining(long remainingMs, long R0ms) {
        long rem = Math.max(0, remainingMs);
        // An unchanged re-note (every plan tick re-notes) carries nothing new: re-anchoring on it
        // would stop the countdown between real changes.
        if (rem != this.annotatedRemainingMs) {
            tickElapsed();
            this.remainingAtElapsedMs = elapsedClockMs;
        }
        this.annotatedRemainingMs = rem;
        if (R0ms > 0) {
            if (this.annotatedR0ms <= 0) {
                // First R0 via residual path — treat as seed.
                this.annotatedR0ms = R0ms;
                tickElapsed();
                seedAtElapsedMs = seedAnchor();
            } else {
                this.annotatedR0ms = R0ms;
            }
        }
        tickElapsed();
        return recompute();
    }

    /**
     * The estimate's likely range as it stands beside the residual {@code remainingMs} it was
     * computed with: kept as the gaps below and above it, so it rides the residual's anchor and
     * decays with the countdown between events.
     */
    public synchronized void noteRange(long remainingMs, long lowMs, long highMs) {
        if (remainingMs < 0 || lowMs < 0 || highMs < 0) return;
        rangeBelowMs = Math.max(0, remainingMs - lowMs);
        rangeAboveMs = Math.max(0, highMs - remainingMs);
    }

    /** How far the likely range reaches below and above the residual; -1 until {@link #noteRange}. */
    private long rangeBelowMs = -1;

    private long rangeAboveMs = -1;

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

    /**
     * The build went on hold (a memory wait): its clock stands still until {@link #resume}, so the
     * bar holds and the countdown resumes from where it stood. A hold is not work.
     */
    public synchronized void hold() {
        if (holdStartNanos != 0) return;
        tickElapsed();
        long now = ticker.nanos();
        holdStartNanos = now == 0 ? 1 : now;
    }

    /** The build resumed: the time held is taken off its clock. */
    public synchronized void resume() {
        if (holdStartNanos == 0) return;
        if (elapsedEpochNanos != 0) elapsedEpochNanos += Math.max(0, ticker.nanos() - holdStartNanos);
        holdStartNanos = 0;
    }

    /** Whether the build is on hold. */
    public synchronized boolean onHold() {
        return holdStartNanos != 0;
    }

    /** When the current hold began on the ticker; 0 when not on hold. */
    private long holdStartNanos;

    private void tickElapsed() {
        // Monotonic synthetic clock via a nanosecond ticker base — wall clock jumps (NTP steps)
        // inflated or froze elapsed when this read the epoch clock. First call establishes the
        // epoch. On hold the clock stands still.
        long nowNanos = ticker.nanos();
        if (elapsedEpochNanos == 0) {
            elapsedEpochNanos = nowNanos;
            elapsedClockMs = 0;
        } else if (holdStartNanos == 0) {
            elapsedClockMs = Math.max(elapsedClockMs, (nowNanos - elapsedEpochNanos) / 1_000_000L);
        }
    }

    // Package-private: WorkspaceProgressTrackerTest rewinds the epoch to simulate elapsed time.
    long elapsedEpochNanos;

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

    /**
     * Until the estimate arrives, execute moves the bar at most to this share. The weights it runs
     * on before then count a fully cached module's slice as done: on jk's own tree they read 72%
     * within a second of a 58 s test run, and the bar's peak hold pinned it there for 37 s until
     * the clock caught up.
     */
    static final double PRE_ESTIMATE_SHARE = 0.15;

    /** How long into execute the bar waits for the estimate before it follows the weights after all. */
    static final long PRE_ESTIMATE_HOLD_MS = 5_000;

    private Snapshot storeWeightAndStrategy(long weightNum, long weightDen, String phase) {
        if (!settled
                && annotatedR0ms <= 0
                && executeStartElapsedMs >= 0
                && elapsedClockMs - executeStartElapsedMs < PRE_ESTIMATE_HOLD_MS
                && weightDen > PREFLIGHT_UNITS) {
            // Execute's share only: the preflight already done stays on the bar.
            long executeCap = Math.round((weightDen - PREFLIGHT_UNITS) * PRE_ESTIMATE_SHARE);
            weightNum = Math.min(weightNum, preflightNum + executeCap);
        }
        HeaderProgressStrategy strat = mode.select(clock, weighted, annotatedR0ms, annotatedRemainingMs);
        // residualRemainingMs: private bar estimate (RemainingWork); -1 until first noteRemaining.
        long residual = annotatedRemainingMs; // already -1 or >=0
        // After seed, remaining was set to R0 — treat as residual only once work has progressed
        // or noteRemaining refreshed it (same field). Always pass it: elapsed/(elapsed+R) at t=0
        // is 0, matching open-loop.
        HeaderProgressState st = new HeaderProgressState(
                weightNum,
                weightDen,
                annotatedR0ms,
                seedAtElapsedMs,
                elapsedClockMs,
                residual,
                settled,
                remainingAtElapsedMs,
                modulesTotal > 0 ? Math.max(0, modulesTotal - modulesComplete) : -1);
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
        long low = -1;
        long high = -1;
        if (annotatedRemainingMs >= 0 && rangeBelowMs >= 0 && !settled) {
            low = Math.max(0, annotatedRemainingMs - rangeBelowMs);
            high = annotatedRemainingMs + rangeAboveMs;
        }
        last = new Snapshot(
                num,
                den,
                percent,
                phase,
                modulesComplete,
                modulesTotal,
                annotatedRemainingMs,
                annotatedR0ms,
                low,
                high);
        return last;
    }

    private String phaseName() {
        if (settled) return "done";
        return executeCalibrated ? "execute" : "preflight";
    }

    public static double preflightFraction(String stage, int done, int total) {
        if (stage == null) stage = "";
        // In the order the stages run. The cache check reports per module forecast: on a 223-module
        // incremental build it is 40% of the wall, and a band below the sync's held the bar still
        // through all of it.
        return switch (stage) {
            // A first lock downloads the project's jars: its settled files move its band.
            case "lock" -> band(0.00, 0.15, done, total);
            case "graph" -> total > 0 && done >= total ? 0.18 : 0.15;
            // A cold sync is most of a first build's preflight: its settled files move a band of
            // their own, between the graph and the check.
            case "sync" -> band(0.18, 0.35, done, total);
            case "checking" -> band(0.35, 0.65, done, total);
            case "plan" -> band(0.65, 0.95, done, total);
            default -> 0.10;
        };
    }

    /** {@code from} plus the share of {@code from..to} that {@code done} of {@code total} is; {@code from} when unknown. */
    private static double band(double from, double to, int done, int total) {
        if (total <= 0) return from;
        double within = Math.min(1.0, Math.max(0.0, (double) done / (double) total));
        return from + (to - from) * within;
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
