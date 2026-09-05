// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Size;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.wire.runtime.progress.HeaderProgressStrategy;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Live console for long-running commands: simple pulse-circle task mode, or plan mode (header
 * with pulse + {@link ProgressBar} + compact module/phase tree). Animates on a TTY; under pipes/{@code
 * --quiet}/{@code --no-progress} only prints the final result. Active {@link LiveRegion} for Ctrl-C.
 *
 * <p>This type is the public TUI facade and the live region itself, and it retains exactly four
 * things: the terminal-region lifecycle — the {@code done} flag and the one {@link #lock} over it,
 * which settle, dismiss, close and the SIGINT {@link #renderCanceled} race to set and each tears
 * the region down differently, so it must have exactly one writer; the header and plan identity
 * (name, target, coordinate, scope hint, wall anchor); the delegations to its collaborators; and
 * the two mode factories. Everything else has one owner: {@link PlanModel} holds the step and
 * phase state, {@link HeaderProgress} the bar's numbers and the solve label, {@link OutputPane} the
 * process-output pane with its key and stream swap, {@link RegionAnimator} the one background
 * loop, {@link WindowTitle} the OSC title; {@link JkManagerView} paints and settles the ANSI frame,
 * {@link JkManagerPlainView} is its {@code --no-ansi} sibling, and {@link JkManagerColor}
 * classifies detail tokens. Owners that touch painted state receive {@link #lock} and take it
 * where this class did; none has a monitor of its own.
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

    /** OSC 0 title with its own glyph cadence; every caller holds {@link #lock}. */
    final WindowTitle windowTitle;

    /** The one background loop for this region; takes {@link #lock} where the manager did. */
    final RegionAnimator animator;

    /** Process-output pane, its Ctrl-O key and the stream swap; takes {@link #lock} where the manager did. */
    final OutputPane pane;

    /** The header bar's numbers and the solve label; takes {@link #lock} where the manager did. */
    final HeaderProgress header;

    /** Step and phase state of the plan; takes {@link #lock} where the manager did. */
    final PlanModel model;

    JkManager(PrintStream out, boolean animate, boolean planMode, int width, ProgressBarMode progressMode) {
        // PlainAscii.wrap is identity under ANSI; under --no-ansi rewrites …/•/● in messages.
        this.out = PlainAscii.wrap(out);
        this.animate = animate;
        this.planMode = planMode;
        this.width = width <= 0 ? DEFAULT_WIDTH : width;
        this.windowTitle = new WindowTitle(this.out, animate);
        this.pane = new OutputPane(this, lock);
        this.header = new HeaderProgress(lock, plain, progressMode, this::elapsedMillis, () -> done);
        this.model = new PlanModel(lock, new PlanEvents());
        this.animator = new RegionAnimator(lock, pane::flushStale, this::tick, plain::maybeEmitHeartbeat, () -> done);
    }

    /** Package-private convenience for tests: the default strategy, no environment read. */
    JkManager(PrintStream out, boolean animate, boolean planMode, int width) {
        this(out, animate, planMode, width, ProgressBarMode.AUTO);
    }

    /** Package-private convenience for simple-mode tests. */
    JkManager(PrintStream out, boolean animate) {
        this(out, animate, false, DEFAULT_WIDTH);
    }

    // --- simple-task mode -------------------------------------------------

    /** Start simple-task mode: spinner (when {@code animate}) + {@code command}. */
    public static JkManager simple(PrintStream out, String command, boolean animate) {
        JkManager cm = new JkManager(out, animate, false, DEFAULT_WIDTH, ProgressBarMode.fromEnvironment());
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
        // JK_PROGRESS_MODE is read here, at the region's start, and nowhere in the header itself.
        JkManager cm = new JkManager(out, animate, true, size.cols(), ProgressBarMode.fromEnvironment());
        cm.height = size.rows();
        cm.name = name;
        cm.startNanos = System.nanoTime();
        LiveRegion.setActive(cm);
        cm.view.ensureLeadingBlank(); // blank line before human chrome
        // config.build-output / JK_BUILD_OUTPUT: start with the process-output peek open.
        if (SessionContext.current().config().buildOutputOr(false)) {
            cm.pane.window().show();
        }
        if (animate && Theme.active().isAnsi()) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.animator.startFrames();
            cm.pane.attachPeekKey(() -> cm.animator.stopped() || cm.done);
        } else if (animate) {
            // Plain plan: stage/ETA/settle lines are event-driven; heartbeat covers long stages.
            cm.animator.startPlainHeartbeat();
        }
        return cm;
    }

    /** Sliding process-output window for this plan (tests / force-show). */
    public OutputWindow outputWindow() {
        return pane.window();
    }

    /**
     * Force-open the process-output pane (non-zero tool/worker exit). No-op when not animating a
     * plan. Does not run for test failures — callers must not invoke this for run-tests.
     */
    public void showProcessFailureOutput() {
        pane.showOnFailure();
    }

    /** Toggle the process-output pane (Ctrl-O). */
    public void toggleOutputWindow() {
        pane.toggle();
    }

    /** Current terminal width in columns (updates on the next paint after a resize). */
    public int width() {
        return width;
    }

    /** Aggregate progress numerator currently driving the bar (for tests/inspection). */
    public long numerator() {
        return header.numerator();
    }

    /** Aggregate progress denominator currently driving the bar (for tests/inspection). */
    public long denominator() {
        return header.denominator();
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

    public void addTask(String module, String stepKey) {
        model.addTask(module, stepKey);
    }

    public void addTaskLabeled(String module, String stepKey, String display) {
        model.addTaskLabeled(module, stepKey, display);
    }

    public void stepRunning(String module, String stepKey) {
        model.stepRunning(module, stepKey);
    }

    public void stepRunning(String module, String stepKey, String phase) {
        model.stepRunning(module, stepKey, phase);
    }

    public void stepMessage(@Nullable String module, String stepKey, String message) {
        model.stepMessage(module, stepKey, message);
    }

    /** A static test finished; the plain view's remaining-test count follows without printing. */
    public void notePlainTestTick(String module, String stepKey, int delta) {
        if (!plain.animating() || !OutputPane.isCuratedTestStep(stepKey)) return;
        model.notePlainTestTick(module, stepKey, delta);
    }

    public void stepDone(@Nullable String module, String stepKey, boolean ok) {
        model.stepDone(module, stepKey, ok);
    }

    public void stepDone(@Nullable String module, String stepKey, boolean ok, String phase) {
        model.stepDone(module, stepKey, ok, phase);
    }

    public void finishModule(String module, boolean ok) {
        model.finishModule(module, ok);
    }

    /** Seed the countdown with remaining wall work {@code R0} (ms); see {@link HeaderProgress}. */
    public void setEtaEstimate(long remainingOrTotalMillis) {
        header.setRemainingWorkEstimate(remainingOrTotalMillis);
    }

    public void setRemainingWorkEstimate(long remainingMillis) {
        header.setRemainingWorkEstimate(remainingMillis);
    }

    public void setBarResidualRemaining(long residualMillis) {
        header.setBarResidualRemaining(residualMillis);
    }

    /** Run-wide total estimate in ms for desktop notifications ({@code 0} = never seeded). */
    public long etaEstimateMs() {
        return header.etaEstimateMs();
    }

    /** Workspace module progress for the header's secondary remaining-work display. */
    public void setModuleProgress(int complete, int total) {
        header.setModuleProgress(complete, total);
    }

    /** Header text instead of the bar while the denominator is still 0; {@code ""} clears it. */
    public void solveLabel(String label) {
        header.solveLabel(label);
    }

    /** Workspace preflight: phase pill for {@code stage} + optional header label. */
    public void preflight(String stage, int done, int total, String label) {
        model.preflight(stage, done, total, label);
    }

    public void attachPhaseError(String module, String stepKey, String phase, String brief) {
        model.attachPhaseError(module, stepKey, phase, brief);
    }

    /** Set the aggregate progress numerator/denominator (engine weight slices). */
    public void progress(long numerator, long denominator) {
        header.progress(numerator, denominator);
    }

    /** Numerator/denominator for the painted bar; see {@link HeaderProgress#displayBar}. */
    long[] displayBar(long elapsedMillis) {
        return header.displayBar(elapsedMillis);
    }

    /** Active strategy for tests/diagnostics. */
    HeaderProgressStrategy activeProgressStrategy() {
        return header.activeProgressStrategy();
    }

    /** Record a finished unit's completion line in the live tail; see {@link PlanModel#addCompletion}. */
    public void addCompletion(String line) {
        synchronized (lock) {
            if (done) return;
            model.addCompletion(line);
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
                    pane.flushVisibleToScrollback();
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

    public static @Nullable String coloredModule(String module) {
        return JkManagerColor.coloredModule(module);
    }

    long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Stop the background loop and release the Ctrl-O key listener. Idempotent. */
    void stopAnimator() {
        animator.stop();
        pane.releasePeekKey();
    }

    // --- helpers ----------------------------------------------------------

    public interface OutputScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Redirect {@code System.out}/{@code System.err} so any process/step output is line-buffered and
     * printed <em>above</em> the live region via {@link #writeAbove}, keeping the region pinned to
     * the bottom. Close the returned scope (try-with-resources) to restore the streams and flush any
     * trailing partial line. No-op when not animating.
     */
    public OutputScope captureOutput() {
        return pane.captureOutput();
    }

    /** Hand the real streams back, flushing a trailing partial line. Idempotent. */
    void restoreStreams() {
        pane.restoreStreams();
    }

    /** The region's answer to model changes: header target, seed lock, plain-view lines. */
    private final class PlanEvents implements PlanModel.Events {
        @Override
        public void stepStarted(String module) {
            target = module;
            // First module task starting = execute has begun: freeze the R0 seed path so
            // provisional eta rewrites cannot thrash the total. Residual still
            // re-anchors the painted countdown.
            header.lockSeed();
            plain.emitPhaseChange();
        }

        @Override
        public void stepMessage(PlanModel.Row row) {
            plain.onStepMessage(row);
        }

        @Override
        public void testTick(PlanModel.Row row, int delta) {
            plain.noteTestTick(row, delta);
        }

        @Override
        public void moduleBuilt(String module) {
            plain.emitModuleBuilt(module);
        }

        @Override
        public void preflight(String pill, int done, int total, String label) {
            header.preflightLabel(pill, done, total, label);
        }
    }
}
