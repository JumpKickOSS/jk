// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

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
        countdown.lockSeed();
    }

    /** The label shown while the bar has no denominator; the preflight pill writes it. Must hold {@code lock}. */
    void preflightLabel(String pill, int done, int total, String label) {
        if (denominator > 0) return;
        if (label != null && !label.isEmpty()) this.solveLabel = label;
        else if (total > 0) this.solveLabel = pill + " " + done + "/" + total;
        else this.solveLabel = pill + "…";
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
            if (this.modulesComplete > 0) countdown.lockSeed();
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
                done.getAsBoolean());
    }
}
