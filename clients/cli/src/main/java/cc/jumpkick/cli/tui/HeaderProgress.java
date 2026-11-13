// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.DurationText;
import cc.jumpkick.wire.runtime.progress.ClockProgressStrategy;
import cc.jumpkick.wire.runtime.progress.HeaderProgressState;
import cc.jumpkick.wire.runtime.progress.HeaderProgressStrategy;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import cc.jumpkick.wire.runtime.progress.SharedPeak;
import cc.jumpkick.wire.runtime.progress.WeightedProgressStrategy;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Turn seeds, residuals and weight slices into the numerator and denominator the header paints, and
 * the solve label shown while the denominator is still zero. Strategy is {@link ProgressBarMode}:
 * under AUTO the clock bar once R0 is seeded, else the weighted bar; one monotonic
 * {@link SharedPeak} across the pair so the AUTO takeover never repaints backwards.
 *
 * <p>The R0 seed lock is the rule that keeps the bar from thrashing mid-build, and it lives in
 * {@link #lockSeed}: the seed path freezes on the first running step or on a completed module, never
 * on the module total alone (the work model carries the total before the engine's real post-forecast
 * seed); the residual re-anchor still applies after the lock. Receives the manager's monitor and
 * takes it exactly where the manager did.
 */
final class HeaderProgress {

    private final Object lock;
    private final JkManagerPlainView plain;
    private final LongSupplier elapsed;
    private final BooleanSupplier done;

    /**
     * Remaining-work anchors and the painted countdown face. Strategy AUTO reads {@link
     * Countdown#r0()} to pick the clock bar; without a residual the bar falls back to
     * {@code elapsed/R0}.
     */
    final Countdown countdown = new Countdown();

    final ProgressBarMode progressMode;

    /** One monotonic floor across the strategy pair — the AUTO takeover must not repaint backwards. */
    final SharedPeak displayedPeak = new SharedPeak();

    final ClockProgressStrategy clockProgress = new ClockProgressStrategy(displayedPeak);
    final WeightedProgressStrategy weightedProgress = new WeightedProgressStrategy(displayedPeak);

    /**
     * When set and {@code denominator == 0}, the header shows this text instead of the progress
     * bar — used by {@code jk lock} to display "Resolving dependencies…" during the PubGrub solve
     * step before the total artifact count is known.
     */
    private volatile String solveLabel = "";

    private long numerator;
    private long denominator;

    /**
     * The engine reports its workspace preflight under way: until it seeds an estimate, nothing says
     * whether this is a no-op, a one-file edit or a cold build, so no bar is honest.
     */
    private boolean estimating;

    /**
     * What the header says while the engine prepares the build. The preflight stage itself
     * ("Checking cache (45/61)") is the work tree's row beneath the header, so the header names the
     * phase rather than repeating the row.
     */
    static final String PREFLIGHT_LABEL = "Initializing…";

    /** The solve label came from a preflight stage, so the header paints {@link #PREFLIGHT_LABEL}. */
    private boolean preflightSolveLabel;

    private int modulesComplete;
    private int modulesTotal; // 0 = hide module remaining

    /**
     * @param progressMode the header bar strategy; the region factories read it from the environment
     *     once, tests pass one
     * @param elapsed wall time since the region started, in ms; owned by the region
     * @param done whether a terminal render already happened
     */
    HeaderProgress(
            Object lock,
            JkManagerPlainView plain,
            ProgressBarMode progressMode,
            LongSupplier elapsed,
            BooleanSupplier done) {
        this.lock = lock;
        this.plain = plain;
        this.progressMode = progressMode;
        this.elapsed = elapsed;
        this.done = done;
    }

    long numerator() {
        return numerator;
    }

    long denominator() {
        return denominator;
    }

    String solveLabel() {
        return solveLabel;
    }

    /** Whether the engine is in its workspace preflight. Must hold {@code lock}. */
    void estimating(boolean estimating) {
        this.estimating = estimating;
    }

    /**
     * No bar yet: the engine is still in its preflight and has seeded no estimate. The header shows
     * the breathing orb, the stage and the clock, and the taskbar the indeterminate state, until the
     * estimate (or execute) arrives. Must hold {@code lock}.
     */
    boolean indeterminate() {
        return estimating && !countdown.seeded();
    }

    /**
     * The text the header paints in place of a bar: {@link #PREFLIGHT_LABEL} through the preflight,
     * whose stage the tree row already shows, else the solve label ({@code jk lock}'s "Resolving
     * dependencies…"). Must hold {@code lock}.
     */
    String headerLabel() {
        if (indeterminate()) return PREFLIGHT_LABEL;
        if (preflightSolveLabel && !solveLabel.isEmpty()) return PREFLIGHT_LABEL;
        return solveLabel;
    }

    int modulesComplete() {
        return modulesComplete;
    }

    int modulesTotal() {
        return modulesTotal;
    }

    /**
     * Freeze the R0 seed path: execute has begun, so provisional eta rewrites cannot thrash the
     * total. Called on the first running step and on the first completed module — never on the
     * module total alone. Must hold {@code lock}.
     */
    void lockSeed() {
        countdown.executeStarted(elapsed.getAsLong());
        countdown.lockSeed();
    }

    /** The label shown while the bar has no denominator; the preflight pill writes it. Must hold {@code lock}. */
    void preflightLabel(String pill, int done, int total, String label) {
        if (denominator > 0) return;
        if (label != null && !label.isEmpty()) this.solveLabel = label;
        else if (total > 0) this.solveLabel = pill + " " + done + "/" + total;
        else this.solveLabel = pill + "…";
        this.preflightSolveLabel = true;
    }

    /**
     * Seed the countdown with remaining wall work {@code R0} (ms). Same figure as {@code jk
     * explain}. After execute starts (first {@link #stepRunning} or a completed module in {@link
     * #setModuleProgress}), further seed-path updates are ignored so a provisional lock-window
     * figure cannot thrash mid-run. Live residual still re-anchors display via {@link
     * #setBarResidualRemaining}. Pre-execute re-seeds (post-forecast, post-prepare) replace a
     * provisional seed while unlocked.
     */
    public void setEtaEstimate(long remainingOrTotalMillis) {
        setRemainingWorkEstimate(remainingOrTotalMillis);
    }

    /**
     * Apply a seed remaining estimate (R0 path). {@code 0} before any seed is ignored (unknown).
     * After execute locks the seed path, updates are ignored — residual mid-run uses {@link
     * #setBarResidualRemaining} instead.
     */
    public void setRemainingWorkEstimate(long remainingMillis) {
        synchronized (lock) {
            if (!countdown.seed(remainingMillis, elapsed.getAsLong())) return;
            this.solveLabel = ""; // R0 is enough to drive the adaptive bar (drop the solve label)
            plain.emitEtaKnown();
        }
    }

    /**
     * Apply live residual remaining from engine RemainingWork. Updates the adaptive clock bar and
     * re-anchors the countdown so painted remaining eases toward residual and hits 0 with it.
     * Between residual samples the paint open-loop-decays residual by wall time. Pass {@code -1}
     * to clear residual (countdown falls back to frozen R0 − elapsed).
     */
    public void setBarResidualRemaining(long residualMillis) {
        synchronized (lock) {
            if (!countdown.residual(residualMillis, elapsed.getAsLong())) return;
            this.solveLabel = "";
            plain.emitEtaKnown();
        }
    }

    /**
     * The estimate's likely range as gaps below and above the residual (JK-4TEZ7): it rides the
     * countdown, so the range decays with it between engine samples.
     */
    public void setEtaRange(long belowMillis, long aboveMillis) {
        synchronized (lock) {
            rangeBelowMs = Math.max(0, belowMillis);
            rangeAboveMs = Math.max(0, aboveMillis);
        }
    }

    private long rangeBelowMs = -1;
    private long rangeAboveMs = -1;

    /** A range is shown in place of the point once it spans more than this share of what remains. */
    static final double WIDE_RANGE = 0.4;

    /**
     * Past this much either side of what remains, on average, a range says nothing a rough figure
     * does not: {@code ~12m (rough)}.
     */
    static final double ROUGH_RANGE = 0.5;

    /** Nor for less than this much left: a few seconds either way is a countdown, not a range. */
    static final long WIDE_RANGE_MIN_MS = 10_000;

    /**
     * The countdown's text for {@code remainingMs} (JK-R0N5S): the plain countdown {@code 1m 32s}
     * when the likely range is narrow (within about ±20%) or little is left; {@code ~1m 20s–1m
     * 50s} when it is wide, since a point would claim a precision the estimate does not have; and
     * {@code ~12m (rough)} past about ±50%, where the range's ends say no more than that. With no
     * range known, {@code ~1m 32s}.
     */
    String etaText(long remainingMs, String dash) {
        long below;
        long above;
        synchronized (lock) {
            below = rangeBelowMs;
            above = rangeAboveMs;
        }
        String point = DurationText.clockMillis(remainingMs);
        if (below < 0) return "~" + point;
        if (remainingMs < WIDE_RANGE_MIN_MS || below + above <= WIDE_RANGE * remainingMs) return point;
        if (below + above > 2 * ROUGH_RANGE * remainingMs) return "~" + point + " (rough)";
        return "~"
                + DurationText.clockMillis(Math.max(0, remainingMs - below))
                + dash
                + DurationText.clockMillis(remainingMs + above);
    }

    /**
     * Run-wide total estimate in ms for desktop notifications ({@code 0} = never seeded).
     * Live countdown prefers residual re-anchor; falls back to R0 − elapsed.
     */
    public long etaEstimateMs() {
        synchronized (lock) {
            return countdown.etaEstimateMs();
        }
    }

    /**
     * Workspace module progress for the header secondary remaining-work display.
     * {@code total <= 0} hides the module counter.
     */
    public void setModuleProgress(int complete, int total) {
        synchronized (lock) {
            this.modulesComplete = Math.max(0, complete);
            this.modulesTotal = Math.max(0, total);
            // A completed module means execute is underway — freeze the R0 seed path.
            // modulesTotal alone arrives with the work model *before* the engine's real
            // post-forecast seed (`eta` line), so it must not lock. Residual
            // re-anchors for display still apply after lock.
            if (this.modulesComplete > 0) {
                countdown.executeStarted(elapsed.getAsLong());
                countdown.lockSeed();
            }
        }
    }

    /**
     * Set a text label shown in the header instead of the progress bar when the denominator is
     * still 0 (pre-solve step). Once {@link #progress} is called with a positive denominator the
     * bar takes over automatically; pass {@code ""} to clear explicitly.
     */
    public void solveLabel(String label) {
        // Same lock as the other solveLabel writers (preflight/progress/seed) — worker and
        // render threads otherwise raced on plain JMM visibility.
        synchronized (lock) {
            this.solveLabel = label == null ? "" : label;
            this.preflightSolveLabel = false;
        }
    }

    /** Set the aggregate progress numerator/denominator (engine weight slices). */
    public void progress(long numerator, long denominator) {
        synchronized (lock) {
            this.numerator = Math.max(0, numerator);
            this.denominator = Math.max(0, denominator);
            HeaderProgressState st = progressState(elapsed.getAsLong());
            HeaderProgressStrategy strat = activeProgressStrategy();
            long[] d = strat.onWeightProgress(st, this.numerator, this.denominator);
            // Weighted strategy owns monotonic peak; keep fields in sync for tests.
            if ("weighted".equals(strat.id()) && d[1] > 0) {
                this.numerator = d[0];
                this.denominator = d[1];
            }
            if (this.denominator > 0 || st.hasR0()) {
                this.solveLabel = "";
            }
            if (d[1] > 0) plain.ensureProgressStarted(d[0], d[1]);
        }
    }

    /**
     * Numerator/denominator for the painted bar via {@link ProgressBarMode} strategy (clock when
     * R0 seeded under AUTO, else weighted; override with {@code JK_PROGRESS_MODE}).
     */
    long[] displayBar(long elapsedMillis) {
        synchronized (lock) {
            return activeProgressStrategy().display(progressState(elapsedMillis));
        }
    }

    /** Active strategy for tests/diagnostics. */
    HeaderProgressStrategy activeProgressStrategy() {
        return progressMode.select(clockProgress, weightedProgress, countdown.r0(), countdown.residual());
    }

    private HeaderProgressState progressState(long elapsedMillis) {
        return new HeaderProgressState(
                numerator,
                denominator,
                countdown.r0(),
                countdown.r0SetAtElapsedMs(),
                elapsedMillis,
                countdown.residual(),
                done.getAsBoolean(),
                countdown.residualSetAtElapsedMs(),
                modulesTotal > 0 ? Math.max(0, modulesTotal - modulesComplete) : -1);
    }
}
