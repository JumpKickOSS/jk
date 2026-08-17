// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.runtime.progress.ClockProgressStrategy;
import cc.jumpkick.runtime.progress.HeaderProgressState;
import cc.jumpkick.runtime.progress.HeaderProgressStrategy;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import cc.jumpkick.runtime.progress.WeightedProgressStrategy;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStyle;
import org.jline.utils.NonBlockingReader;

/**
 * Live console for long-running commands: simple pulse-circle task mode, or plan mode (header
 * with pulse + {@link ProgressBar} + compact module/phase tree). Animates on a TTY; under pipes/{@code
 * --quiet}/{@code --no-progress} only prints the final result. Active {@link LiveRegion} for Ctrl-C.
 *
 * <p>Paint/settle live in {@link JkManagerView}; token coloring in {@link JkManagerColor}. This
 * type stays the public TUI facade (under 1,200: the live region owns animation state).
 */
public final class JkManager implements AutoCloseable, LiveRegion {

    static final String PULSE = Spinner.PULSE_GLYPH;
    static final int PULSE_FRAMES = Spinner.PULSE_FRAMES;
    static final long FRAME_MS = Spinner.FRAME_MS;

    /** Flush a captured partial line (no newline yet) after this much quiet. */
    private static final long STALE_FLUSH_MS = 360;

    static final String ELLIPSIS = "…";
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
     * {@link TerminalSize} each frame so a mid-build SIGWINCH updates truncation budgets without
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
    final AttributedStyle[] openPulseColors = Spinner.buildOpenPulseStyles(PULSE_FRAMES);

    /** Chip pulse (white↔chip blue) — plan header pill only; FG sits on solid chip BG. */
    final Object lock = new Object();

    final JkManagerView view = new JkManagerView(this);

    volatile boolean stopped; // animator should stop
    boolean done; // a terminal render already happened
    int frame;
    int linesDrawn; // plan mode: lines in the live region
    List<String> lastLines = List.of(); // plan mode: last painted lines, for diffing
    /** true after the leading blank of the human chrome envelope was printed. */
    boolean leadingBlankPrinted;

    /**
     * Plain ({@code --no-ansi}) multi-line progress: last printed 20% step (0, 20, …, 80), or -1
     * before the mandatory 0% start line. 100% is only emitted as a done line on settle.
     */
    int plainLastDecade = -1;

    /** True after any plain working/progress line has been printed for this region. */
    boolean plainChromeStarted;

    /** True when aggregate progress (den &gt; 0) drove plain chrome — settle uses 100% done. */
    boolean plainProgressMode;

    // simple mode
    String label = "";

    // plan mode
    String name = "";
    String target = "";
    // Package-private: JkManagerTest rewinds the wall anchor to simulate elapsed time.
    long startNanos;
    long numerator;
    long denominator;
    /**
     * Frozen seed remaining {@code R0} at seed time ({@code -1} = unknown / count-up only). Paired
     * with {@link #remainingSetAtElapsedMs}. Explain-identical; seed path freezes after execute.
     * Strategy AUTO uses this to pick clock; bar falls back to {@code elapsed/R0} without residual.
     *
     * <p>Header bar mode: {@link ProgressBarMode} ({@code JK_PROGRESS_MODE}) — default AUTO uses
     * {@link ClockProgressStrategy} when R0 is seeded, else {@link WeightedProgressStrategy}.
     */
    long remainingWorkMs = -1;
    /** {@link #elapsedMillis()} when the R0 seed was taken. */
    long remainingSetAtElapsedMs;
    /**
     * Live residual remaining from engine RemainingWork ({@code -1} unknown). Drives the adaptive
     * clock bar ({@code elapsed/(elapsed+residual)}) and the painted countdown (re-anchored).
     */
    long residualRemainingMs = -1;
    /**
     * {@link #elapsedMillis()} when {@link #residualRemainingMs} was last applied — countdown
     * open-loop-decays residual between samples so it eases into R(t) and hits 0 with residual.
     */
    long residualSetAtElapsedMs;
    /**
     * Jitter buffer for the painted countdown face: residual may re-anchor many times inside one
     * whole second, but the header only commits a new remaining figure when {@link
     * #countdownDisplayElapsedSec} advances (or on first paint / seed / snap-to-zero). Holds the
     * last painted remaining seconds.
     */
    long countdownDisplayRemainingSec;
    /**
     * Whole-second elapsed for which {@link #countdownDisplayRemainingSec} was sampled ({@code -1}
     * = never painted — next planHeader samples the latest target immediately).
     */
    long countdownDisplayElapsedSec = -1;
    /**
     * True once execute has begun — the explain seed path ({@link #setRemainingWorkEstimate}) no
     * longer replaces R0. Residual re-anchors for display still apply.
     */
    boolean openLoopLocked;
    /** Run-wide total for notifications: elapsed-at-seed + R0. 0 when never seeded. */
    long etaEstimateMs;

    final ProgressBarMode progressMode = ProgressBarMode.fromEnvironment();
    /** One monotonic floor across the strategy pair — the AUTO takeover must not repaint backwards. */
    final cc.jumpkick.runtime.progress.SharedPeak displayedPeak = new cc.jumpkick.runtime.progress.SharedPeak();

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

    /**
     * OSC window title base (no spinner prefix), e.g. {@code JumpKick - Building g:a:v...}. The
     * fill-circle glyph is prepended and the OSC is re-emitted only when that glyph advances
     * ({@link Spinner#FILL_HOLD} cadence), not every animator frame.
     */
    String windowTitleBase = "";

    /** Last fill glyph written into the OSC title; null until first emit. */
    String windowTitleLastGlyph;

    /** True after {@link #setWindowTitle} until cleared on settle/dismiss/cancel. */
    boolean windowTitleActive;

    Thread animator;

    // output capture: while active, System.out/err are redirected so process
    // output prints above the region (see captureOutput / restoreStreams).
    PrintStream savedOut;
    PrintStream savedErr;
    volatile LineSink sink; // read by the animator thread for stale flushing
    boolean capturing;

    /**
     * Sliding process-output buffer for plan mode (Ctrl-O peek / force-show on tool failure). Always
     * present; only plan+animate installs the key listener.
     */
    final OutputWindow outputWindow = new OutputWindow();

    // Ctrl-O key listener (plan mode, interactive TTY only)
    private Terminal keyTerminal;
    private Attributes keyAttrsSaved;
    private Thread keyThread;
    private volatile boolean keysStopped;

    JkManager(PrintStream out, boolean animate, boolean planMode, int width) {
        // PlainAscii.wrap is identity under ANSI; under --no-ansi rewrites …/•/● in messages.
        this.out = PlainAscii.wrap(out);
        this.animate = animate;
        this.planMode = planMode;
        this.width = width <= 0 ? DEFAULT_WIDTH : width;
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
        cm.ensureLeadingBlank(); // blank line before human chrome
        if (animate && Theme.active().isAnsi()) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
        } else if (animate) {
            // Plain multi-line: mandatory start line.
            cm.printPlainIndeterminate(true);
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
        int[] size = animate ? TerminalSize.refresh() : new int[] {DEFAULT_HEIGHT, DEFAULT_WIDTH};
        JkManager cm = new JkManager(out, animate, true, size[1]);
        cm.height = size[0];
        cm.name = name;
        cm.startNanos = System.nanoTime();
        LiveRegion.setActive(cm);
        cm.ensureLeadingBlank(); // blank line before human chrome
        // config.build-output / JK_BUILD_OUTPUT: start with the process-output peek open.
        if (cc.jumpkick.config.SessionContext.current().config().buildOutputOr(false)) {
            cm.outputWindow.show();
        }
        if (animate && Theme.active().isAnsi()) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
            cm.startKeyListener();
        }
        // Plain plan: no start line until progress() or settle (message may not exist yet).
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
                paintBuildPlan();
                out.flush();
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

    /**
     * Set the terminal window/tab title (OSC 0) for the life of this live region. The fill-circle
     * spinner glyph is prefixed ({@code "○ JumpKick - …"}); OSC is updated only when that glyph
     * changes (same hold cadence as tree-row spinners). Cleared automatically on settle / dismiss /
     * cancel / close.
     *
     * <p>Build passes the base string {@code JumpKick - Building g:a:v...} (no glyph).
     */
    public void setWindowTitle(String title) {
        synchronized (lock) {
            // Only an interactive ANSI terminal gets OSC 0 — under pipes/--quiet (!animate)
            // or no-ANSI mode (--no-ansi, TERM=dumb, CI) the escapes would land verbatim in
            // the output stream.
            if (done || !animate || !Theme.active().isAnsi() || !Ansi.oscEnabled()) return;
            windowTitleBase = title == null ? "" : title;
            windowTitleActive = !windowTitleBase.isEmpty();
            windowTitleLastGlyph = null; // force immediate emit with current fill glyph
            emitWindowTitleIfGlyphChanged();
            out.flush();
        }
    }

    /**
     * Emit OSC 0 with {@code fillGlyph + " " + base} only when the fill phase actually advances.
     * Must hold {@link #lock}.
     */
    void emitWindowTitleIfGlyphChanged() {
        if (!windowTitleActive || windowTitleBase.isEmpty()) return;
        String glyph = Spinner.fillGlyph(frame);
        if (glyph.equals(windowTitleLastGlyph)) return;
        windowTitleLastGlyph = glyph;
        out.print(Ansi.windowTitle(glyph + " " + windowTitleBase));
    }

    /** Clear a title set by {@link #setWindowTitle}, if any. */
    void clearWindowTitle() {
        if (!windowTitleActive) return;
        windowTitleActive = false;
        windowTitleBase = "";
        windowTitleLastGlyph = null;
        out.print(Ansi.windowTitleClear());
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
            if (remainingWorkMs >= 0) openLoopLocked = true;
        }
    }

    /** Set the current sub-task message for a running step. */
    public void stepMessage(String module, String stepKey, String message) {
        synchronized (lock) {
            Row r = rows.get(key(module, stepKey));
            if (r != null) r.message = message == null ? "" : message;
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
        long rem = Math.max(0, remainingMillis);
        synchronized (lock) {
            if (openLoopLocked) return;
            // Unknown → still unknown: ignore a bare zero (engine "no estimate").
            if (remainingWorkMs < 0 && rem == 0) return;
            // Already seeded: ignore zero (do not clear R0). Positive re-seeds allowed pre-execute.
            if (remainingWorkMs >= 0 && rem == 0) return;
            long elapsed = elapsedMillis();
            remainingWorkMs = rem;
            remainingSetAtElapsedMs = elapsed;
            etaEstimateMs = elapsed + rem;
            // Pre-execute re-seed also refreshes residual so countdown tracks the refined R0
            // until live RemainingWork updates arrive (provisional → post-forecast).
            residualRemainingMs = rem;
            residualSetAtElapsedMs = elapsed;
            // Force the countdown face to re-sample on next paint (seed is intentional, not jitter).
            countdownDisplayElapsedSec = -1;
            // R0 is enough to drive the adaptive bar (drop preflight solve label).
            if (rem > 0) this.solveLabel = "";
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
            if (residualMillis < 0) {
                residualRemainingMs = -1;
                return;
            }
            long rem = residualMillis;
            // Bare residual 0 with no R0 seed: do not invent a dual clock from "done".
            if (remainingWorkMs < 0 && rem == 0) return;
            // Identical re-emit carries no new information — keep the existing anchor so the
            // promised open-loop decay between samples actually happens. Without this, the
            // preflight ticks force-emitting an unchanged R0 every ~500 ms re-anchored the
            // countdown each time and froze the face at R0 for the whole prepare window,
            // silently pushing the real finish to executeStart + R0.
            if (rem == residualRemainingMs) return;
            long elapsed = elapsedMillis();
            residualRemainingMs = rem;
            residualSetAtElapsedMs = elapsed;
            // Reconnect / residual-before-seed: seed R0 from first positive residual.
            if (remainingWorkMs < 0 && rem > 0) {
                remainingWorkMs = rem;
                remainingSetAtElapsedMs = elapsed;
            }
            if (etaEstimateMs == 0 && rem > 0) {
                etaEstimateMs = elapsed + rem;
            }
            if (rem > 0) this.solveLabel = "";
        }
    }

    /**
     * Run-wide total estimate in ms for desktop notifications ({@code 0} = never seeded).
     * Live countdown prefers residual re-anchor; falls back to R0 − elapsed.
     */
    public long etaEstimateMs() {
        synchronized (lock) {
            return etaEstimateMs;
        }
    }

    /** Wall-clock ms since this manager was constructed (run-wide). */
    public long elapsedMillisPublic() {
        return elapsedMillis();
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
            if (this.modulesComplete > 0 && remainingWorkMs >= 0) {
                openLoopLocked = true;
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
                msg = msg.substring(0, cut) + ELLIPSIS;
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
            if (animate && !Theme.active().isAnsi() && d[1] > 0) {
                emitPlainProgressDecades(d[0], d[1]);
            }
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
        return progressMode.select(clockProgress, weightedProgress, remainingWorkMs, residualRemainingMs);
    }

    ProgressBarMode progressMode() {
        return progressMode;
    }

    private HeaderProgressState progressState(long elapsedMillis) {
        return new HeaderProgressState(
                numerator,
                denominator,
                remainingWorkMs,
                remainingSetAtElapsedMs,
                elapsedMillis,
                residualRemainingMs,
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
        view.finishSuccess(message);
    }

    public void finishSuccess(String message, List<String> above) {
        view.finishSuccess(message, above);
    }

    public void finishBuildPlanSuccess(String tail, List<String> above) {
        view.finishBuildPlanSuccess(tail, above);
    }

    public void finishBuildPlanSuccess(String tail) {
        view.finishBuildPlanSuccess(tail);
    }

    public void finishBuildPlanExec(String tail, List<String> above) {
        view.finishBuildPlanExec(tail, above);
    }

    public void finishBuildPlanExec(String tail) {
        view.finishBuildPlanExec(tail);
    }

    public void finishBuildPlanFailure(String tail, List<String> above) {
        view.finishBuildPlanFailure(tail, above);
    }

    public void finishBuildPlanFailure(String tail) {
        view.finishBuildPlanFailure(tail);
    }

    public void finishBuildPlanCancelled(List<String> above) {
        view.finishBuildPlanCancelled(above);
    }

    public void finishBuildPlanCancelled() {
        view.finishBuildPlanCancelled();
    }

    public void finishBuildPlanFailureCustom(String sentence, List<String> above) {
        view.finishBuildPlanFailureCustom(sentence, above);
    }

    public void finishFailure(String message) {
        view.finishFailure(message);
    }

    public void finishFailure(String message, List<String> above) {
        view.finishFailure(message, above);
    }

    public void dismiss() {
        view.dismiss();
    }

    String planName() {
        return view.planName();
    }

    void settle(String line) {
        view.settle(line);
    }

    void settle(String line, List<String> above) {
        view.settle(line, above);
    }

    void emitPlainProgressDecades(long num, long den) {
        view.emitPlainProgressDecades(num, den);
    }

    static String plainProgressLine(String command, String message, int percent, boolean done) {
        return JkManagerView.plainProgressLine(command, message, percent, done);
    }

    static String plainIndeterminateLine(String command, String message, boolean done) {
        return JkManagerView.plainIndeterminateLine(command, message, done);
    }

    public void writeAbove(String text) {
        view.writeAbove(text);
    }

    /**
     * True when a failed step should force-open the process-output pane (tool/worker crash), not
     * when the failure is a curated test-runner result.
     */
    public static boolean forceShowOnStepFailure(String step, String group) {
        // Only the test-runner step uses curated failure chrome; everything else is a tool/worker.
        if (step == null) return true;
        return !step.equals(cc.jumpkick.run.TaskNames.RUN_TESTS) && !step.startsWith("run-tests");
    }

    public List<String> renderBuildPlanLines(int cols, long elapsedMillis) {
        return view.renderBuildPlanLines(cols, elapsedMillis);
    }

    RenderContext headerContext() {
        return view.headerContext();
    }

    void ensureLeadingBlank() {
        view.ensureLeadingBlank();
    }

    void printPlainIndeterminate(boolean forceStart) {
        view.printPlainIndeterminate(forceStart);
    }

    void printPlainDone() {
        view.printPlainDone();
    }

    public String canceledMessage() {
        return "Build job was cancelled";
    }

    @Override
    public boolean renderCanceled() {
        // Ctrl-C: hand the streams back so any buffered output flushes above the
        // region, stop animating, then settle. BuildPlan mode replaces the wiped region
        // in place with the same cancelled-job wedge as a remote `jk cancel` / web cancel
        // ("‼ Build  job was cancelled by user took …") and returns true so GlobalCancel
        // suppresses its generic notice. Simple / non-animating modes just settle and let
        // the handler print the notice.
        restoreStreams();
        stopAnimator();
        synchronized (lock) {
            if (done) return true;
            done = true;
            LiveRegion.clearActive(this);
            clearWindowTitle();
            if (!animate) return false;
            if (planMode) {
                if (Theme.active().isAnsi()) {
                    flushVisibleOutputToScrollback();
                    wipeRegion();
                    out.print(Ansi.taskbarClear());
                    out.print(Ansi.SHOW_CURSOR);
                } else {
                    printPlainDone();
                }
                // Ctrl-C: "by user" + took duration.
                String took = cc.jumpkick.cli.run.ConsoleSpec.took(Duration.ofMillis(elapsedMillis()));
                out.println(JkWedge.cancelled(planName(), true, took).renderLine(headerContext()));
                out.flush();
                return true;
            }
            if (Theme.active().isAnsi()) {
                freezeSpinnerLine();
                out.print(Ansi.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
            } else {
                printPlainDone();
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
            clearWindowTitle();
            if (!animate) {
                out.flush();
                return;
            }
            if (Theme.active().isAnsi()) {
                wipeRegion();
                out.print(Ansi.taskbarClear());
                out.print(Ansi.SHOW_CURSOR);
            } else {
                printPlainDone();
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
        out.print(ELLIPSIS);
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
            if (planMode) paintBuildPlan();
            else paintSimple();
            // OSC title tracks the fill-circle phase (○→◎→◉→◎), not every chip-pulse frame.
            emitWindowTitleIfGlyphChanged();
            out.flush();
            // One counter; chip pulse and fill-circle use different period via floorMod.
            frame++;
        }
    }

    void paintBuildPlan() {
        view.paintBuildPlan();
    }

    void paintSimple() {
        view.paintSimple();
    }

    static String fmtClock(long millis) {
        return JkManagerColor.fmtClock(millis);
    }

    static String fmtClockSeconds(long totalSec) {
        return JkManagerColor.fmtClockSeconds(totalSec);
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

    // --- animation --------------------------------------------------------

    private void startAnimator() {
        animator = new Thread(this::loop, "jk-command-manager");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        try {
            while (!stopped) {
                // Flush a captured partial line that's gone quiet (no newline),
                // OUTSIDE the render lock so the order matches step writes
                // (sink → lock) and can't deadlock with tick (lock only).
                LineSink s = sink;
                if (s != null) s.maybeFlushStale(STALE_FLUSH_MS);
                tick();
                Thread.sleep(FRAME_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void stopAnimator() {
        stopped = true;
        stopKeyListener();
        Thread a;
        synchronized (lock) {
            a = animator;
            animator = null;
        }
        if (a != null) {
            a.interrupt();
            try {
                a.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Non-blocking Ctrl-O listener on the controlling TTY. ISIG stays on so Ctrl-C still raises
     * SIGINT for {@link GlobalCancel}. Best-effort: if the terminal cannot be opened, peek is
     * unavailable for this plan.
     */
    private void startKeyListener() {
        if (!planMode || !animate || !Interactivity.canPrompt()) return;
        try {
            Terminal t = Interactivity.takeSharedTerminal();
            if (t == null) {
                t = Wizard.openTerminal();
            }
            Attributes saved = t.getAttributes();
            Attributes raw = new Attributes(saved);
            raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            // ISIG remains: Ctrl-C → SIGINT → GlobalCancel. Do not call
            // {@code terminal.handle(INT, …)} — that would steal the signal from GlobalCancel
            // the same way JLine's default native SIG_DFL handlers did.
            t.setAttributes(raw);
            GlobalCancel.install();
            Wizard.drainInput(t.reader(), 40L);
            keyTerminal = t;
            keyAttrsSaved = saved;
            keysStopped = false;
            keyThread = new Thread(this::readKeys, "jk-output-keys");
            keyThread.setDaemon(true);
            keyThread.start();
        } catch (Exception ignored) {
            // Peek is optional — plan continues without Ctrl-O.
            keyTerminal = null;
            keyAttrsSaved = null;
        }
    }

    private void stopKeyListener() {
        keysStopped = true;
        Thread kt = keyThread;
        keyThread = null;
        if (kt != null) {
            kt.interrupt();
            try {
                kt.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Terminal t = keyTerminal;
        Attributes saved = keyAttrsSaved;
        keyTerminal = null;
        keyAttrsSaved = null;
        if (t != null && saved != null) {
            try {
                Wizard.restoreCooked(t, saved);
            } catch (RuntimeException ignored) {
                // best-effort
            }
            // NEVER close: the system terminal owns FD 0 (see Interactivity) — closing it here
            // broke stdin for everything after the plan in the same invocation (jk run's
            // inheritIO app, wizard prompts, the next plan's Ctrl-O). Return it for reuse.
            Interactivity.returnSharedTerminal(t);
        }
    }

    private void readKeys() {
        Terminal t = keyTerminal;
        if (t == null) return;
        NonBlockingReader reader = t.reader();
        while (!keysStopped && !stopped && !done) {
            try {
                KeyReader.Key key = KeyReader.readOrNull(reader, 100L);
                if (key instanceof KeyReader.Key.CtrlO) {
                    toggleOutputWindow();
                }
                // Ctrl-C is handled by the signal path (ISIG); ignore other keys.
            } catch (RuntimeException e) {
                return; // reader closed / failed
            }
        }
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

    /** "compile-java" → "Compile java"; "runTests" → "RunTests" (best-effort). */
    static String detailForDisplay(String module, String message) {
        return JkManagerColor.detailForDisplay(module, message);
    }

    static String renderBriefErrorLine(boolean last, String brief) {
        return JkManagerColor.renderBriefErrorLine(last, brief);
    }

    static String colorDetail(String phase, String detail, Theme t) {
        return JkManagerColor.colorDetail(phase, detail, t);
    }

    static boolean looksLikeJavaMember(String s) {
        return JkManagerColor.looksLikeJavaMember(s);
    }

    static boolean looksLikePathOrArtifact(String tok) {
        return JkManagerColor.looksLikePathOrArtifact(tok);
    }

    static boolean looksLikeCoord(String tok) {
        return JkManagerColor.looksLikeCoord(tok);
    }

    static String humanize(String stepKey) {
        return JkManagerColor.humanize(stepKey);
    }

    static String fmtElapsed(long millis) {
        return JkManagerColor.fmtElapsed(millis);
    }

    static String truncateVisible(String s, int maxCols) {
        return JkManagerColor.truncateVisible(s, maxCols);
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
        synchronized (lock) {
            if (!animate || capturing) return () -> {};
            savedOut = System.out;
            savedErr = System.err;
            sink = new LineSink(this);
            PrintStream redirect = new PrintStream(sink, true, StandardCharsets.UTF_8);
            System.setOut(redirect);
            System.setErr(redirect);
            capturing = true;
        }
        return this::restoreStreams;
    }

    /**
     * Restore the real {@code System.out}/{@code System.err} and flush any trailing partial line
     * above the region. Idempotent — called by the {@link OutputScope}, and defensively when the
     * region settles (so a Ctrl-C mid-plan hands the streams back before {@link GlobalCancel}
     * prints).
     */
    void restoreStreams() {
        LineSink toFlush = null;
        synchronized (lock) {
            if (!capturing) return;
            capturing = false;
            System.setOut(savedOut);
            System.setErr(savedErr);
            toFlush = sink;
        }
        if (toFlush != null) toFlush.flushPartial(); // may writeAbove (re-locks)
    }

    /** Buffers redirected bytes and forwards each completed line to {@link #writeAbove}. */
    static final class LineSink extends OutputStream {
        private final JkManager cm;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long lastWriteNanos; // when the current partial line last grew

        LineSink(JkManager cm) {
            this.cm = cm;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                buf.write(b);
                lastWriteNanos = System.nanoTime();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int start = off;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    buf.write(b, start, i - start);
                    emit();
                    start = i + 1;
                }
            }
            if (start < off + len) {
                buf.write(b, start, off + len - start);
                lastWriteNanos = System.nanoTime();
            }
        }

        /**
         * Flush a buffered partial line that hasn't grown for {@code ms} (a sliding window reset on
         * each write) — so output without a trailing newline still appears in a timely manner instead
         * of stalling.
         */
        synchronized void maybeFlushStale(long ms) {
            if (buf.size() == 0) return;
            if (System.nanoTime() - lastWriteNanos < ms * 1_000_000L) return;
            emit();
        }

        synchronized void flushPartial() {
            if (buf.size() > 0) emit();
        }

        private void emit() {
            String s = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            if (s.isBlank()) return; // do not inject empty lines into the peek / settle layout
            cm.writeAbove(s);
        }
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
