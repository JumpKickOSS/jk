// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Size;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.wire.runtime.progress.ClockProgressStrategy;
import cc.jumpkick.wire.runtime.progress.HeaderProgressState;
import cc.jumpkick.wire.runtime.progress.HeaderProgressStrategy;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import cc.jumpkick.wire.runtime.progress.SharedPeak;
import cc.jumpkick.wire.runtime.progress.WeightedProgressStrategy;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live console for long-running commands: simple pulse-circle task mode, or plan mode (header
 * with pulse + {@link ProgressBar} + compact module/phase tree). Animates on a TTY; under pipes/{@code
 * --quiet}/{@code --no-progress} only prints the final result. Active {@link LiveRegion} for Ctrl-C.
 *
 * <p>This type is the public TUI facade and the live region itself. Its collaborators own one
 * thing each: {@link JkManagerView} paints and settles the ANSI frame, {@link JkManagerPlainView}
 * is its {@code --no-ansi} sibling on the mode axis, {@link JkManagerColor} classifies detail
 * tokens, {@link OutputWindow} rings process output and {@link PeekKeys} is its Ctrl-O key,
 * {@link OutputCapture} owns the {@code System.out} swap, and {@link Countdown} owns the
 * remaining-work anchors. What stays here is the {@code done} flag and the one lock over it —
 * settle, dismiss, close and the SIGINT {@link #renderCanceled} race to set it and each tears the
 * region down differently, so it must have exactly one writer (, under 1,200).
 */
public final class JkManager implements AutoCloseable, LiveRegion {

    static final String PULSE = Spinner.PULSE_GLYPH;
    static final int PULSE_FRAMES = Spinner.PULSE_FRAMES;
    static final long FRAME_MS = Spinner.FRAME_MS;

    /** Flush a captured partial line (no newline yet) after this much quiet. */
    private static final int DEFAULT_WIDTH = 80;

    private static final int DEFAULT_HEIGHT = 24;

    /** Max step rows shown before completed rows collapse into a "+N" line. */
    static final int MAX_ROWS = 8;

    /** Max completed lines shown below the active tree before a "… plus N more …" footer. */
    static final int MAX_COMPLETIONS = 5;

    final PrintStream out;
    final boolean animate;
    final boolean planMode;

    /**
     * Terminal columns. Seeded at plan start; {@link JkManagerView#paintBuildPlan()} re-reads
     * {@link Size} each frame so a mid-build SIGWINCH updates truncation budgets without
     * waiting for the next plan.
     */
    int width;

    /**
     * Terminal rows. The whole region must fit within this — a region taller than the viewport
     * scrolls its top into scrollback, and cursor-relative repaint/wipe ({@code cursorUp(n)}) clamps
     * at the viewport top and can no longer reach it (leaving stale lines, e.g. a lingering spinner
     * on cancel). Re-read with {@link #width} on each plan paint after a resize.
     */
    int height = DEFAULT_HEIGHT; // package-private: tests set it directly

    /**
     * nerd-font caps — gate the powerline pill header. Package-private: tests set it directly.
     */
    NerdFontCaps nerdFont = GlobalConfig.nerdFont();
    /**
     * When set and {@code denominator == 0}, the header shows this text instead of the progress
     * bar — used by {@code jk lock} to display "Resolving dependencies…" during the PubGrub solve
     * step before the total artifact count is known.
     */
    volatile String solveLabel = "";

    /** Open pulse (blue↔dark blue) — tree rows and simple spinner lines, no chip background. */
    final Style[] openPulseColors = Spinner.buildOpenPulseStyles(PULSE_FRAMES);

    /**
     * The one monitor for this region's state. The views and every owner extracted from this class
     * that touches painted state synchronize on this same object, never on one of their own — a
     * paint that took two locks could tear a frame or deadlock.
     */
    final Object lock = new Object();

    final JkManagerView view = new JkManagerView(this);

    /** The {@code --no-ansi} half of the mode axis; inert whenever ANSI is live. */
    final JkManagerPlainView plain = new JkManagerPlainView(this);

    boolean done; // a terminal render already happened
    int frame;
    int linesDrawn; // plan mode: lines in the live region
    List<String> lastLines = List.of(); // plan mode: last painted lines, for diffing
    /** true after the leading blank of the human chrome envelope was printed. */
    boolean leadingBlankPrinted;

    // simple mode
    String label = "";

    // plan mode
    String name = "";
    String target = "";
    /**
     * Workspace / root {@code group:name} shown on the first plain prepare line. Sticky — not
     * overwritten when a member step starts.
     */
    String planCoord = "";
    /**
     * Gerund for the module-selection caption ({@code building}, {@code testing}, …). Empty when
     * the plan is the whole workspace.
     */
    String scopeHintVerb = "";
    /** Project {@code name}s shown in the caption. Empty when unset. */
    List<String> scopeHintNames = List.of();
    // Package-private: JkManagerTest rewinds the wall anchor to simulate elapsed time.
    long startNanos;
    long numerator;
    long denominator;
    /**
     * Remaining-work anchors and the painted countdown face. Strategy AUTO reads {@link
     * Countdown#r0()} to pick the clock bar; without a residual the bar falls back to
     * {@code elapsed/R0}. Header bar mode is {@link ProgressBarMode} ({@code JK_PROGRESS_MODE}) —
     * AUTO uses {@link ClockProgressStrategy} when R0 is seeded, else {@link
     * WeightedProgressStrategy}.
     */
    final Countdown countdown = new Countdown();

    final ProgressBarMode progressMode = ProgressBarMode.fromEnvironment();
    /** One monotonic floor across the strategy pair — the AUTO takeover must not repaint backwards. */
    final SharedPeak displayedPeak = new SharedPeak();

    final ClockProgressStrategy clockProgress = new ClockProgressStrategy(displayedPeak);
    final WeightedProgressStrategy weightedProgress = new WeightedProgressStrategy(displayedPeak);
    int modulesComplete;
    int modulesTotal; // 0 = hide module remaining
    long finishSeq;

    final Map<String, Row> rows = new LinkedHashMap<>();

    /** Coarse plan phases in first-seen order (render newest-first). Key = wire phase name. */
    final List<String> phaseOrder = new ArrayList<>();

    final Map<String, PhaseNode> phases = new LinkedHashMap<>();

    /** Pre-formatted completion lines, oldest→newest; bounded to {@link #MAX_COMPLETIONS}. */
    final List<String> recentCompletions = new ArrayList<>();

    int completedCount;

    /** OSC 0 title with its own glyph cadence; every caller holds {@link #lock}. */
    final WindowTitle windowTitle;

    /** The one background loop for this region; takes {@link #lock} where the manager did. */
    final RegionAnimator animator;

    /** System.out/err redirection while the region is up — see {@link #captureOutput}. */
    private final OutputCapture capture = new OutputCapture(this::writeProcessOutput);

    /**
     * Sliding process-output buffer for plan mode (Ctrl-O peek / force-show on tool failure). Always
     * present; only plan+animate installs the key listener.
     */
    final OutputWindow outputWindow = new OutputWindow();

    /**
     * Ctrl-O peek listener (plan mode on an interactive TTY only); {@code null} when the controlling
     * terminal is unavailable. Written by the plan-starting thread, read by whichever thread settles
     * — often the SIGINT handler (renderCanceled → stopAnimator). {@link PeekKeys} owns the terminal
     * attributes and the atomicity of giving them back.
     */
    private volatile PeekKeys keys;

    JkManager(PrintStream out, boolean animate, boolean planMode, int width) {
        // PlainAscii.wrap is identity under ANSI; under --no-ansi rewrites …/•/● in messages.
        this.out = PlainAscii.wrap(out);
        this.animate = animate;
        this.planMode = planMode;
        this.width = width <= 0 ? DEFAULT_WIDTH : width;
        this.windowTitle = new WindowTitle(this.out, animate);
        this.animator = new RegionAnimator(lock, capture, this::tick, plain::maybeEmitHeartbeat, () -> done);
    }

    /** Package-private convenience for simple-mode tests. */
    JkManager(PrintStream out, boolean animate) {
        this(out, animate, false, DEFAULT_WIDTH);
    }

    // --- simple-task mode -------------------------------------------------

    /** Start simple-task mode: spinner (when {@code animate}) + {@code command}. */
    public static JkManager simple(PrintStream out, String command, boolean animate) {
        JkManager cm = new JkManager(out, animate, false, DEFAULT_WIDTH);
        cm.label = command;
        LiveRegion.setActive(cm);
        cm.view.ensureLeadingBlank(); // blank line before human chrome
        if (animate && Theme.active().isAnsi()) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.animator.startFrames();
        } else if (animate) {
            // Plain multi-line: mandatory start line.
            cm.plain.printIndeterminate(true);
        }
        return cm;
    }

    /** Update the running command/label (simple mode). */
    public void label(String command) {
        synchronized (lock) {
            this.label = command == null ? "" : command;
        }
    }

    // --- plan-oriented mode -----------------------------------------------

    /**
     * Start plan-oriented mode. {@code name} is the command shown in the header (e.g. {@code
     * "Building"}); set the active module with {@link #target}.
     */
    public static JkManager plan(PrintStream out, String name, boolean animate) {
        // Probe here — a plan start is a natural boundary — never from the frame-render path.
        Size.Window size = animate ? Size.refresh() : new Size.Window(DEFAULT_HEIGHT, DEFAULT_WIDTH);
        JkManager cm = new JkManager(out, animate, true, size.cols());
        cm.height = size.rows();
        cm.name = name;
        cm.startNanos = System.nanoTime();
        LiveRegion.setActive(cm);
        cm.view.ensureLeadingBlank(); // blank line before human chrome
        // config.build-output / JK_BUILD_OUTPUT: start with the process-output peek open.
        if (SessionContext.current().config().buildOutputOr(false)) {
            cm.outputWindow.show();
        }
        if (animate && Theme.active().isAnsi()) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.animator.startFrames();
            cm.keys = PeekKeys.attach(cm::toggleOutputWindow, () -> cm.animator.stopped() || cm.done);
        } else if (animate) {
            // Plain plan: stage/ETA/settle lines are event-driven; heartbeat covers long stages.
            cm.animator.startPlainHeartbeat();
        }
        return cm;
    }

    /** Sliding process-output window for this plan (tests / force-show). */
    public OutputWindow outputWindow() {
        return outputWindow;
    }

    /**
     * Force-open the process-output pane (non-zero tool/worker exit). No-op when not animating a
     * plan. Does not run for test failures — callers must not invoke this for run-tests.
     */
    public void showProcessFailureOutput() {
        if (!planMode) return;
        synchronized (lock) {
            if (done) return;
            boolean wasOpen = outputWindow.visible();
            outputWindow.show();
            if (animate && Theme.active().isAnsi() && !wasOpen) {
                view.openPeekPaint();
            } else if (animate && Theme.active().isAnsi()) {
                view.requestFullRepaint();
                view.paintBuildPlan();
                out.flush();
            } else if (!Theme.active().isAnsi()
                    && !SessionContext.current().config().verboseOr(false)) {
                // Plain mode buffers tool stdout (suppressed unless -v); a crash is the one
                // moment it must surface — verbose already printed it live.
                plain.dumpProcessOutput();
            }
        }
    }

    /** Toggle the process-output pane (Ctrl-O). */
    public void toggleOutputWindow() {
        if (!planMode) return;
        synchronized (lock) {
            if (done) return;
            if (outputWindow.visible()) {
                outputWindow.hide();
                if (animate && Theme.active().isAnsi()) view.closePeekPaint();
            } else {
                outputWindow.show();
                if (animate && Theme.active().isAnsi()) view.openPeekPaint();
            }
        }
    }

    /** Current terminal width in columns (updates on the next paint after a resize). */
    public int width() {
        return width;
    }

    /** Aggregate progress numerator currently driving the bar (for tests/inspection). */
    public long numerator() {
        return numerator;
    }

    /** Aggregate progress denominator currently driving the bar (for tests/inspection). */
    public long denominator() {
        return denominator;
    }

    /** Header module, e.g. {@code "acme:api"}. */
    public void target(String module) {
        synchronized (lock) {
            this.target = module == null ? "" : module;
        }
    }

    /** Set the workspace/root coordinate used by plain {@code prepare} lines. */
    public void setPlanCoord(String coord) {
        synchronized (lock) {
            this.planCoord = coord == null ? "" : coord;
        }
    }

    /**
     * Caption above the wedge: {@code  …building module jk-cli…}. Empty {@code verb} or {@code
     * names} clears it.
     */
    public void setModuleScopeHint(String verb, List<String> names) {
        synchronized (lock) {
            scopeHintVerb = verb == null ? "" : verb;
            scopeHintNames = names == null || names.isEmpty() ? List.of() : List.copyOf(names);
        }
    }

    /**
     * Set the terminal window/tab title (OSC 0) for the life of this live region. A half-circle
     * spinner glyph is prefixed ({@code "◐ JumpKick - …"}); OSC is re-emitted only when that glyph
     * swaps on its own 500ms cadence. Cleared automatically on settle / dismiss / cancel / close.
     *
     * <p>Build passes the base string {@code JumpKick - Building g:a:v...} (no glyph).
     */
    public void setWindowTitle(String title) {
        synchronized (lock) {
            if (done) return;
            windowTitle.set(title);
        }
    }

    /** Register a not-yet-started step row with a humanized display name. */
    public void addTask(String module, String stepKey) {
        addTaskLabeled(module, stepKey, humanize(stepKey));
    }

    /** Register a not-yet-started step row with an explicit display label. */
    public void addTaskLabeled(String module, String stepKey, String display) {
        synchronized (lock) {
            rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, display, stepKey));
        }
    }

    /** Mark a step running (phase defaults to the step key). */
    public void stepRunning(String module, String stepKey) {
        stepRunning(module, stepKey, "");
    }

    /**
     * Mark a step running and record its coarse {@code phase} (wire name, e.g. {@code compile}) for
     * the vertical phase chain. Empty phase falls back to the step key (same as the web dashboard).
     *
     * <p>No-ops when the step already has a terminal status so a late/out-of-order {@code stepStart}
     * cannot resurrect a finished row (same rule as the dashboard rehydrate path).
     */
    public void stepRunning(String module, String stepKey, String phase) {
        synchronized (lock) {
            String phaseKey = phaseKey(phase, stepKey);
            Row r = rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, humanize(stepKey), phaseKey));
            if (r.state == RowState.DONE || r.state == RowState.FAILED) return;
            r.phase = phaseKey;
            r.state = RowState.ACTIVE;
            this.target = module;
            touchPhaseStart(phaseKey);
            // First module task starting = execute has begun: freeze the R0 seed path so
            // provisional eta rewrites cannot thrash the total. Residual still
            // re-anchors the painted countdown.
            countdown.lockSeed();
            plain.emitPhaseChange();
        }
    }

    /** Set the current sub-task message for a running step. */
    public void stepMessage(String module, String stepKey, String message) {
        synchronized (lock) {
            Row r = rows.get(key(module, stepKey));
            if (r == null) return;
            r.message = message == null ? "" : message;
            plain.onStepMessage(r);
        }
    }

    /**
     * A static test finished. Decrements the plain remaining-test count without printing — the next
     * stage-change or 30s heartbeat line shows the updated {@code running N tests}.
     */
    public void notePlainTestTick(String module, String stepKey, int delta) {
        if (!plain.animating() || !isCuratedTestStep(stepKey)) return;
        synchronized (lock) {
            Row r = rows.get(key(module, stepKey));
            if (r != null) plain.noteTestTick(r, delta);
        }
    }

    /** Mark a step finished (phase defaults empty). */
    public void stepDone(String module, String stepKey, boolean ok) {
        stepDone(module, stepKey, ok, "");
    }

    /** Mark a step finished; updates the phase chain aggregate for {@code phase}. */
    public void stepDone(String module, String stepKey, boolean ok, String phase) {
        synchronized (lock) {
            String phaseKey = phaseKey(phase, stepKey);
            Row r = rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, humanize(stepKey), phaseKey));
            if (r.state == RowState.DONE || r.state == RowState.FAILED) return;
            r.phase = phaseKey;
            r.state = ok ? RowState.DONE : RowState.FAILED;
            r.message = "";
            r.seq = ++finishSeq;
            touchPhaseFinish(phaseKey, ok);
        }
    }

    /**
     * Terminal cleanup for one module's plan: any still-{@link RowState#ACTIVE} rows (lost wire
     * {@code task-finish}, cancel between start and finish, …) settle so they leave the live tree
     * instead of lingering for the rest of the workspace build.
     */
    public void finishModule(String module, boolean ok) {
        synchronized (lock) {
            if (module == null) return;
            for (Row r : rows.values()) {
                if (!module.equals(r.module) || r.state != RowState.ACTIVE) continue;
                r.state = ok ? RowState.DONE : RowState.FAILED;
                r.message = "";
                r.seq = ++finishSeq;
                if (r.phase != null && !r.phase.isEmpty()) {
                    touchPhaseFinish(r.phase, ok);
                }
            }
            if (ok) plain.emitModuleBuilt(module);
        }
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
            if (!countdown.seed(remainingMillis, elapsedMillis())) return;
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
            if (!countdown.residual(residualMillis, elapsedMillis())) return;
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

    /**
     * Workspace preflight: phase pill for {@code stage} + optional header label while the bar may
     * still be preflight-only. Called from {@link cc.jumpkick.cli.run.AggregateContext#preflight}.
     */
    public void preflight(String stage, int done, int total, String label) {
        synchronized (lock) {
            String key = stage == null || stage.isEmpty() ? "checking" : stage;
            String pill = phaseLabel(key);
            PhaseNode n = phases.get(key);
            if (n == null) {
                n = new PhaseNode(key, pill);
                phases.put(key, n);
                phaseOrder.add(key);
            }
            // Prefer an explicit label; else "3/10" style progress on the tree row detail.
            if (label != null && !label.isEmpty()) n.detail = label;
            else if (total > 0) n.detail = done + "/" + total;
            boolean complete = total > 0 && done >= total;
            if (complete) {
                // Success → drop from the live chain (failed preflight keeps a red row).
                phases.remove(key);
                phaseOrder.remove(key);
            } else {
                n.state = PhaseState.RUNNING;
            }
            if (denominator <= 0) {
                if (label != null && !label.isEmpty()) this.solveLabel = label;
                else if (total > 0) this.solveLabel = pill + " " + done + "/" + total;
                else this.solveLabel = pill + "…";
            }
        }
    }

    /**
     * Attach a short failure summary to the step/phase owning {@code stepKey} (or {@code phase} if
     * set). Full diagnostics still go above the region / result files.
     */
    public void attachPhaseError(String module, String stepKey, String phase, String brief) {
        synchronized (lock) {
            String msg = brief == null ? "" : brief.trim().replace('\n', ' ');
            if (msg.length() > 96) {
                int cut = 93;
                // Never split a surrogate pair: an emoji-heavy assertion brief cut mid-pair
                // ends in a lone high surrogate (mojibake in the tree's brief-error row, and
                // PlainAscii passes lone surrogates through).
                if (Character.isHighSurrogate(msg.charAt(cut - 1))) cut--;
                msg = msg.substring(0, cut) + Glyphs.ELLIPSIS;
            }
            Row r = rows.get(key(module, stepKey));
            if (r != null && !msg.isEmpty()) r.briefError = msg;
            // Prefer the row's recorded wire phase (e.g. "compile") when callers pass empty
            // phase or a step key that has no PhaseNode.
            String pk = phaseKey(phase, stepKey);
            if (r != null && r.phase != null && !r.phase.isEmpty()) {
                if (pk == null || pk.isEmpty() || !phases.containsKey(pk)) {
                    pk = r.phase;
                }
            }
            if (pk == null || pk.isEmpty()) return;
            PhaseNode n = phases.get(pk);
            if (n == null) return;
            if (!msg.isEmpty()) n.briefError = msg;
            n.anyFailed = true;
            n.state = PhaseState.FAILED;
        }
    }

    /** Set the aggregate progress numerator/denominator (engine weight slices). */
    public void progress(long numerator, long denominator) {
        synchronized (lock) {
            this.numerator = Math.max(0, numerator);
            this.denominator = Math.max(0, denominator);
            HeaderProgressState st = progressState(elapsedMillis());
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
                done);
    }

    /**
     * Record a finished unit's pre-formatted completion line in the live tail under the wedge
     * (newest first, capped to {@link #MAX_COMPLETIONS}; the rest collapse into a
     * {@code … plus N more …} footer). Does not write to the terminal or to process-output
     * scrollback — the next plan paint includes it in the live region. Callers that aren't
     * animating should print append-only instead (see {@link #animating}).
     */
    public void addCompletion(String line) {
        synchronized (lock) {
            if (done) return;
            completedCount++;
            recentCompletions.add(line);
            if (recentCompletions.size() > MAX_COMPLETIONS) recentCompletions.remove(0);
        }
    }

    /** True when the region animates live (interactive tty); false under pipes / {@code --quiet}. */
    public boolean animating() {
        return animate;
    }

    // --- completion / paint (JkManagerView) --------------------------------

    public void finishSuccess(String message) {
        view.finishSuccess(message, List.of());
    }

    public void finishSuccess(String message, List<String> above) {
        view.finishSuccess(message, above);
    }

    public void finishBuildPlanSuccess(String tail, List<String> above) {
        view.finishBuildPlanSuccess(tail, above);
    }

    public void finishBuildPlanSuccess(String tail) {
        view.finishBuildPlanSuccess(tail, List.of());
    }

    public void finishBuildPlanExec(String tail, List<String> above) {
        view.finishBuildPlanExec(tail, above);
    }

    public void finishBuildPlanExec(String tail) {
        view.finishBuildPlanExec(tail, List.of());
    }

    public void finishBuildPlanFailure(String tail, List<String> above) {
        view.finishBuildPlanFailure(tail, above);
    }

    public void finishBuildPlanFailure(String tail) {
        view.finishBuildPlanFailure(tail, List.of());
    }

    public void finishBuildPlanCancelled(List<String> above) {
        view.finishBuildPlanCancelled(above);
    }

    public void finishBuildPlanCancelled() {
        view.finishBuildPlanCancelled(List.of());
    }

    public void finishBuildPlanFailureCustom(String sentence, List<String> above) {
        view.finishBuildPlanFailureCustom(sentence, above);
    }

    public void finishFailure(String message) {
        view.finishFailure(message, List.of());
    }

    public void finishFailure(String message, List<String> above) {
        view.finishFailure(message, above);
    }

    public void dismiss() {
        view.dismiss();
    }

    /** The plan/command name shown in the header ("Building", "Locking", …). */
    String planName() {
        String n = planMode ? name : label;
        return n == null ? "" : n;
    }

    void settle(String line, List<String> above) {
        view.settle(line, above);
    }

    /** Package-private for tests and the heartbeat thread — one beat under the manager lock. */
    void maybeEmitPlainHeartbeat() {
        synchronized (lock) {
            plain.maybeEmitHeartbeat();
        }
    }

    public void writeAbove(String text) {
        view.writeAbove(text);
    }

    /**
     * Tool/process stdout (compiler, tests, native-image). In plain {@code --no-ansi} this is
     * suppressed unless {@code -v}/{@code --verbose}; diagnostics still use {@link #writeAbove}.
     */
    public void writeProcessOutput(String text) {
        view.writeProcessOutput(text);
    }

    /** A failed tool/worker step force-opens the process-output pane; a curated test failure does not. */
    public static boolean forceShowOnStepFailure(String step) {
        return !isCuratedTestStep(step);
    }

    /**
     * The curated test-runner step ({@code run-tests} + forks) — keyed on step identity, never the
     * step's group: compile-test failures are group Test too and must force-open (tool output).
     */
    private static boolean isCuratedTestStep(String stepKey) {
        return stepKey != null && stepKey.startsWith(TaskNames.RUN_TESTS);
    }

    public List<String> renderBuildPlanLines(int cols, long elapsedMillis) {
        return view.renderBuildPlanLines(cols, elapsedMillis);
    }

    public String canceledMessage() {
        return "Build job was cancelled";
    }

    @Override
    public boolean renderCanceled() {
        // Ctrl-C: hand the streams back so any buffered output flushes above the
        // region, stop animating, then settle. BuildPlan mode replaces the wiped region
        // in place with the same cancelled-job wedge as a remote `jk cancel` / web cancel
        // ("⊛ Build  job was cancelled by user took …") and returns true so GlobalCancel
        // suppresses its generic notice. Simple / non-animating modes just settle and let
        // the handler print the notice.
        restoreStreams();
        stopAnimator();
        synchronized (lock) {
            if (done) return true;
            done = true;
            LiveRegion.clearActive(this);
            windowTitle.clear();
            if (!animate) return false;
            if (planMode) {
                if (Theme.active().isAnsi()) {
                    flushVisibleOutputToScrollback();
                    wipeRegion();
                    out.print(Osc.taskbarClear());
                    out.print(Ansi.SHOW_CURSOR);
                } else {
                    plain.printDone();
                }
                // Ctrl-C: "by user" + took duration.
                String took = ConsoleSpec.took(Duration.ofMillis(elapsedMillis()));
                out.println(JkWedge.cancelled(planName(), true, took).renderLine(view.headerContext()));
                out.flush();
                return true;
            }
            if (Theme.active().isAnsi()) {
                freezeSpinnerLine();
                out.print(Osc.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
            } else {
                plain.printDone();
            }
            out.flush();
            return false;
        }
    }

    @Override
    public void close() {
        restoreStreams();
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            windowTitle.clear();
            if (!animate) {
                out.flush();
                return;
            }
            if (Theme.active().isAnsi()) {
                wipeRegion();
                out.print(Osc.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
            } else {
                plain.printDone();
            }
            out.flush();
        }
    }

    /** Simple mode: repaint the spinner line with its first (settled) glyph, then newline. */
    void freezeSpinnerLine() {
        out.print('\r');
        out.print(Theme.colorize(PULSE, openPulseColors[0]));
        out.print(' ');
        out.print(label);
        out.print(Glyphs.ELLIPSIS);
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print('\n');
    }

    /** Erase the live region and park the cursor at its top-left. */
    void wipeRegion() {
        if (planMode) {
            if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
            // Return to column 0 first: cursorUp preserves the column, and on a
            // Ctrl-C the tty has just echoed "^C" at the cursor (two columns in),
            // so a bare ERASE_DISPLAY_TO_END would leave the first two columns of
            // the top line — the spinner glyph — on screen.
            out.print('\r');
            out.print(Ansi.ERASE_DISPLAY_TO_END);
            linesDrawn = 0;
            lastLines = List.of();
        } else {
            out.print(Ansi.CLEAR_LINE);
        }
    }

    // --- rendering --------------------------------------------------------

    /** Render one frame in place and advance the spinner. Package-private for tests. */
    void tick() {
        synchronized (lock) {
            if (done || !animate) return;
            if (planMode) view.paintBuildPlan();
            else view.paintSimple();
            // OSC title swaps its half-circle glyph (◐↔◑) on its own 500ms cadence.
            windowTitle.emitIfDue(System.currentTimeMillis());
            out.flush();
            // Frame counter for chip pulse and fill-circle tree rows; the title keeps its own time.
            frame++;
        }
    }

    private static String phaseKey(String phase, String stepKey) {
        if (phase != null && !phase.isEmpty()) return phase;
        return stepKey == null ? "" : stepKey;
    }

    private void touchPhaseStart(String phaseKey) {
        if (phaseKey == null || phaseKey.isEmpty()) return;
        PhaseNode n = phases.get(phaseKey);
        if (n == null) {
            n = new PhaseNode(phaseKey, phaseLabel(phaseKey));
            phases.put(phaseKey, n);
            phaseOrder.add(phaseKey);
        }
        n.runningCount++;
        n.state = PhaseState.RUNNING;
    }

    private void touchPhaseFinish(String phaseKey, boolean ok) {
        if (phaseKey == null || phaseKey.isEmpty()) return;
        PhaseNode n = phases.get(phaseKey);
        if (n == null) {
            n = new PhaseNode(phaseKey, phaseLabel(phaseKey));
            phases.put(phaseKey, n);
            phaseOrder.add(phaseKey);
        }
        n.runningCount = Math.max(0, n.runningCount - 1);
        if (!ok) {
            n.anyFailed = true;
            if (n.briefError == null || n.briefError.isEmpty()) n.briefError = "Failed";
        }
        if (n.runningCount == 0) {
            if (n.anyFailed) {
                n.state = PhaseState.FAILED;
            } else {
                // Success → remove from the live chain (web-like: only active/failed stay visible)
                phases.remove(phaseKey);
                phaseOrder.remove(phaseKey);
            }
        } else {
            n.state = n.anyFailed ? PhaseState.FAILED : PhaseState.RUNNING;
        }
    }

    /** {@code compile} → {@code Compile}. */
    static String phaseLabel(String wire) {
        return JkManagerColor.phaseLabel(wire);
    }

    public static String coloredModule(String module) {
        return JkManagerColor.coloredModule(module);
    }

    long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Stop the background loop and release the Ctrl-O key listener. Idempotent. */
    void stopAnimator() {
        animator.stop();
        PeekKeys k = keys;
        if (k != null) k.close();
    }

    /**
     * Peek close for settle/cancel: process lines are already permanent scrollback above the live
     * region — hide the pane so wipe clears separator+wedge (not a re-dump of the log). The settle
     * path then prints one blank between that scrollback and the settle chip when any lines were
     * committed.
     */
    void flushVisibleOutputToScrollback() {
        synchronized (lock) {
            if (!outputWindow.visible()) return;
            // Lines were committed above the region as they arrived; leave them in scrollback.
            outputWindow.hide();
        }
    }

    // --- helpers ----------------------------------------------------------

    private static String key(String module, String stepKey) {
        return module + '\0' + stepKey;
    }

    /** {@code compile-java} → {@code Compile java} for a step row's display label. */
    static String humanize(String stepKey) {
        return JkManagerColor.humanize(stepKey);
    }

    public interface OutputScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Redirect {@code System.out}/{@code System.err} so any process/step output is line-buffered and
     * printed <em>above</em> the live region via {@link #writeAbove}, keeping the region pinned to
     * the bottom. The region itself keeps painting to the original (captured) stdout, so there's no
     * recursion. Close the returned scope (try-with-resources) to restore the streams and flush any
     * trailing partial line. No-op when not animating.
     */
    public OutputScope captureOutput() {
        if (!animate || !capture.start()) return () -> {};
        return capture::restore;
    }

    /** Hand the real streams back, flushing a trailing partial line. Idempotent. */
    void restoreStreams() {
        capture.restore();
    }

    enum RowState {
        PENDING,
        ACTIVE,
        DONE,
        FAILED
    }

    enum PhaseState {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    static final class PhaseNode {
        final String key;
        final String label;
        PhaseState state = PhaseState.PENDING;
        int runningCount;
        boolean anyFailed;
        /** One-line summary under a failed phase (full diagnostics stay in result files). */
        String briefError = "";
        /** Live sub-task for preflight / phase-only rows (e.g. artifact being fetched). */
        String detail = "";

        PhaseNode(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static final class Row {
        final String module;
        final String step;
        String phase;
        RowState state = RowState.PENDING;
        String message = "";
        /** One-line failure summary under a failed row (full diagnostics stay above / in files). */
        String briefError = "";
        /** Plain status override ({@code compiling 12 sources} / {@code running 80 tests}). */
        String plainStatusOverride = "";
        /** Remaining static tests for plain countdown; {@code -1} = unknown. */
        int plainRemainingTests = -1;

        long seq;

        Row(String module, String step, String phase) {
            this.module = module;
            this.step = step;
            this.phase = phase == null ? "" : phase;
        }
    }

    /** One compact tree line (+ optional brief under failed work). */
    static final class TreeEntry {
        final String line;
        final String briefError;

        TreeEntry(String line, String briefError) {
            this.line = line;
            this.briefError = briefError == null ? "" : briefError;
        }
    }
}
