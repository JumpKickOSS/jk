// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jline.utils.AttributedStyle;

/**
 * Live console for long-running commands: simple pulse-circle task mode, or pipeline mode (header
 * with pulse + {@link ProgressBar} + compact module/phase tree). Animates on a TTY; under pipes/{@code
 * --quiet}/{@code --no-progress} only prints the final result. Active {@link LiveRegion} for Ctrl-C.
 */
public final class CommandManager implements AutoCloseable, LiveRegion {

    private static final String PULSE = Spinner.PULSE_GLYPH;
    private static final int PULSE_FRAMES = Spinner.PULSE_FRAMES;
    private static final long FRAME_MS = Spinner.FRAME_MS;

    /** Flush a captured partial line (no newline yet) after this much quiet. */
    private static final long STALE_FLUSH_MS = 360;

    private static final String ELLIPSIS = "…";
    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;

    /** Max step rows shown before completed rows collapse into a "+N" line. */
    static final int MAX_ROWS = 8;

    /** Max completed lines shown below the active tree before a "… plus N more …" footer. */
    static final int MAX_COMPLETIONS = 5;

    private final PrintStream out;
    private final boolean animate;
    private final boolean pipelineMode;
    private final int width;

    /**
     * Terminal rows. The whole region must fit within this — a region taller than the viewport
     * scrolls its top into scrollback, and cursor-relative repaint/wipe ({@code cursorUp(n)}) clamps
     * at the viewport top and can no longer reach it (leaving stale lines, e.g. a lingering spinner
     * on cancel).
     */
    int height = DEFAULT_HEIGHT; // package-private: tests set it directly

    /**
     * [global].nerdfont — gates the powerline pill header. Package-private: tests set it directly.
     */
    boolean nerdfont = GlobalConfig.nerdfont();
    /**
     * When set and {@code denominator == 0}, the header shows this text instead of the progress
     * bar — used by {@code jk lock} to display "Resolving dependencies…" during the PubGrub solve
     * step before the total artifact count is known.
     */
    private volatile String solveLabel = "";

    /** Open pulse (blue↔dark blue) — tree rows and simple spinner lines, no chip background. */
    private final AttributedStyle[] openPulseColors = Spinner.buildOpenPulseStyles(PULSE_FRAMES);

    /** Chip pulse (white↔chip blue) — pipeline header pill only; FG sits on solid chip BG. */
    private final AttributedStyle[] chipPulseColors =
            Spinner.buildChipPulseStyles(PULSE_FRAMES, Theme.active().planBadgeColor());

    private final ProgressBar bar = new ProgressBar();

    private final Object lock = new Object();
    private volatile boolean stopped; // animator should stop
    private boolean done; // a terminal render already happened
    private int frame;
    private int linesDrawn; // pipeline mode: lines in the live region
    private List<String> lastLines = List.of(); // pipeline mode: last painted lines, for diffing

    // simple mode
    private String label = "";

    // pipeline mode
    private String name = "";
    private String target = "";
    private long startNanos;
    private long numerator;
    private long denominator;
    private double peakFraction; // monotonic-display floor: the bar never renders below this
    private long etaEstimateMs; // total predicted build wall-clock (the jk explain figure); 0 = no countdown
    private int modulesComplete;
    private int modulesTotal; // 0 = hide module remaining
    private long finishSeq;

    private final Map<String, Row> rows = new LinkedHashMap<>();

    /** Coarse pipeline phases in first-seen order (render newest-first). Key = wire phase name. */
    private final List<String> phaseOrder = new ArrayList<>();

    private final Map<String, PhaseNode> phases = new LinkedHashMap<>();

    /** Pre-formatted completion lines, oldest→newest; bounded to {@link #MAX_COMPLETIONS}. */
    private final List<String> recentCompletions = new ArrayList<>();

    private int completedCount;

    /**
     * OSC window title base (no spinner prefix), e.g. {@code JumpKick - Building g:a:v...}. The
     * fill-circle glyph is prepended and the OSC is re-emitted only when that glyph advances
     * ({@link Spinner#FILL_HOLD} cadence), not every animator frame.
     */
    private String windowTitleBase = "";

    /** Last fill glyph written into the OSC title; null until first emit. */
    private String windowTitleLastGlyph;

    /** True after {@link #setWindowTitle} until cleared on settle/dismiss/cancel. */
    private boolean windowTitleActive;

    private Thread animator;

    // output capture: while active, System.out/err are redirected so process
    // output prints above the region (see captureOutput / restoreStreams).
    private PrintStream savedOut;
    private PrintStream savedErr;
    private volatile LineSink sink; // read by the animator thread for stale flushing
    private boolean capturing;

    CommandManager(PrintStream out, boolean animate, boolean pipelineMode, int width) {
        this.out = out;
        this.animate = animate;
        this.pipelineMode = pipelineMode;
        this.width = width <= 0 ? DEFAULT_WIDTH : width;
    }

    /** Package-private convenience for simple-mode tests. */
    CommandManager(PrintStream out, boolean animate) {
        this(out, animate, false, DEFAULT_WIDTH);
    }

    // --- simple-task mode -------------------------------------------------

    /** Start simple-task mode: spinner (when {@code animate}) + {@code command}. */
    public static CommandManager simple(PrintStream out, String command, boolean animate) {
        CommandManager cm = new CommandManager(out, animate, false, DEFAULT_WIDTH);
        cm.label = command;
        LiveRegion.setActive(cm);
        if (animate) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
        }
        return cm;
    }

    /** Update the running command/label (simple mode). */
    public void label(String command) {
        synchronized (lock) {
            this.label = command == null ? "" : command;
        }
    }

    // --- pipeline-oriented mode -----------------------------------------------

    /**
     * Start pipeline-oriented mode. {@code name} is the command shown in the header (e.g. {@code
     * "Building"}); set the active module with {@link #target}.
     */
    public static CommandManager pipeline(PrintStream out, String name, boolean animate) {
        int[] size = animate ? detectSize() : new int[] {DEFAULT_HEIGHT, DEFAULT_WIDTH};
        CommandManager cm = new CommandManager(out, animate, true, size[1]);
        cm.height = size[0];
        cm.name = name;
        cm.startNanos = System.nanoTime();
        LiveRegion.setActive(cm);
        if (animate) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
        }
        return cm;
    }

    /** Terminal width detected at construction (columns). */
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
            if (done || !animate || !Theme.active().isAnsi()) return;
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
    private void emitWindowTitleIfGlyphChanged() {
        if (!windowTitleActive || windowTitleBase.isEmpty()) return;
        String glyph = Spinner.fillGlyph(frame);
        if (glyph.equals(windowTitleLastGlyph)) return;
        windowTitleLastGlyph = glyph;
        out.print(Ansi.windowTitle(glyph + " " + windowTitleBase));
    }

    /** Clear a title set by {@link #setWindowTitle}, if any. */
    private void clearWindowTitle() {
        if (!windowTitleActive) return;
        windowTitleActive = false;
        windowTitleBase = "";
        windowTitleLastGlyph = null;
        out.print(Ansi.WINDOW_TITLE_CLEAR);
    }

    /** Register a not-yet-started step row with a humanized display name. */
    public void addStep(String module, String stepKey) {
        addStepLabeled(module, stepKey, humanize(stepKey));
    }

    /** Register a not-yet-started step row with an explicit display label. */
    public void addStepLabeled(String module, String stepKey, String display) {
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
     */
    public void stepRunning(String module, String stepKey, String phase) {
        synchronized (lock) {
            String phaseKey = phaseKey(phase, stepKey);
            Row r = rows.computeIfAbsent(key(module, stepKey), k -> new Row(module, humanize(stepKey), phaseKey));
            r.phase = phaseKey;
            r.state = RowState.ACTIVE;
            this.target = module;
            touchPhaseStart(phaseKey);
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
            r.phase = phaseKey;
            r.state = ok ? RowState.DONE : RowState.FAILED;
            r.message = "";
            r.seq = ++finishSeq;
            touchPhaseFinish(phaseKey, ok);
        }
    }

    /**
     * Seed the header clock with the total predicted build wall-clock (the same figure {@code jk
     * explain} reports). The clock is <em>run-wide</em> and pure wall-clock from {@link
     * #pipeline(PrintStream, String, boolean) construction}:
     *
     * <ul>
     * <li>With a seed {@code > 0}: count down {@code seed − elapsed} one second per real second;
     * at overrun flip to {@code +Ns} count-up of the excess.
     * <li>With no seed ({@code 0}): count up {@code +Ns} from {@code +0s} for the whole command.
     * </ul>
     *
     * <p>Early + post-prepare seeds may refine the total while no module has finished yet. Once
     * execute has completed any module, further updates are ignored so mid-build re-projections
     * cannot jump the countdown or reset count-up at module boundaries.
     */
    public void setEtaEstimate(long totalMillis) {
        synchronized (lock) {
            long next = Math.max(0, totalMillis);
            // Never clear a positive seed with 0 (unknown) mid-run.
            if (next == 0 && etaEstimateMs > 0) return;
            // After any module finishes, lock the seed for pure wall-clock display.
            if (etaEstimateMs > 0 && modulesComplete > 0) return;
            this.etaEstimateMs = next;
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
        }
    }

    /**
     * Set a text label shown in the header instead of the progress bar when the denominator is
     * still 0 (pre-solve step). Once {@link #progress} is called with a positive denominator the
     * bar takes over automatically; pass {@code ""} to clear explicitly.
     */
    public void solveLabel(String label) {
        this.solveLabel = label == null ? "" : label;
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
            if (msg.length() > 96) msg = msg.substring(0, 93) + "…";
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

    /** Set the aggregate progress numerator/denominator for the bar. */
    public void progress(long numerator, long denominator) {
        synchronized (lock) {
            // Monotonic display guard: at a STABLE total, never let the rendered
            // fraction slide backward — a residual reweight that drops num/den holds
            // at the peak until real progress passes it. But when the total GROWS
            // (the uncalibrated path discovering more modules, or genuine new work)
            // the fraction legitimately rebases, so reset the peak instead of pinning
            // at 100%. The calibrated workspace build fixes its total up front
            // (Step 1.5), so there the total is stable and the guard is always live.
            double f = denominator > 0 ? (double) numerator / denominator : 0.0;
            if (denominator > this.denominator) {
                peakFraction = f; // total grew → rebase
            } else if (denominator > 0 && f < peakFraction) {
                numerator = Math.round(peakFraction * denominator); // hold the peak
            } else {
                peakFraction = f;
            }
            this.numerator = numerator;
            this.denominator = denominator;
            // Prefer the bar over the text-only solve label once we have a denominator.
            if (denominator > 0) this.solveLabel = "";
        }
    }

    /**
     * Record a finished unit's pre-formatted completion line in the live completed-tail rendered
     * below the active rows (newest first, capped to {@link #MAX_COMPLETIONS}; the rest collapse into
     * a "… plus N more …" footer). Callers that aren't animating should print append-only instead
     * (see {@link #animating}) — this only feeds the live region.
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

    // --- completion -------------------------------------------------------

    /** Settle with {@code ✔ <pipeline> Successful: <message>} (the head in green). */
    public void finishSuccess(String message) {
        finishSuccess(message, List.of());
    }

    /**
     * Like {@link #finishSuccess(String)}, but first prints {@code above} — buffered subprocess
     * output (compiler warnings, &c.) — as scrollback above the result line, so the {@code ✔
     * Successful} summary is the last thing the user sees. The lines land after the live region is
     * wiped, under one lock, so they never interleave with the bar.
     */
    public void finishSuccess(String message, List<String> above) {
        String head = Glyphs.CHECK + (pipelineName().isEmpty() ? "" : " " + pipelineName()) + " Successful";
        settle(Theme.colorize(head, Theme.active().success()) + ": " + message, above);
    }

    /**
     * Settle the build pipeline with the green chip: {@code ✓ Build ▶ Successfully <tail>}. The {@code
     * tail} (e.g. "built 17 modules took 1.4s") is pre-styled by the caller; this owns only the chip
     * + cap + command. See {@link PipelineWedge}.
     */
    public void finishPipelineSuccess(String tail, List<String> above) {
        settle(PipelineWedge.chipLine(Glyphs.CHECK, pipelineName(), nerdfont, tail), above);
    }

    /** {@link #finishPipelineSuccess(String, List)} with no buffered output above. */
    public void finishPipelineSuccess(String tail) {
        finishPipelineSuccess(tail, List.of());
    }

    /**
     * Settle with the play chip: {@code ▶ Run Executing `java …`} — for commands that hand off to a
     * subprocess after the pipeline settles (e.g. {@code jk run}). {@code pipelineName} is the
     * command label (typically {@code Run}); {@code tail} is the pre-styled message.
     */
    public void finishPipelineExec(String tail, List<String> above) {
        settle(PipelineWedge.chipLine(Glyphs.PLAY, pipelineName(), nerdfont, tail), above);
    }

    /** {@link #finishPipelineExec(String, List)} with no buffered output above. */
    public void finishPipelineExec(String tail) {
        finishPipelineExec(tail, List.of());
    }

    /** Settle the build pipeline with the red chip: {@code ‼ Build ▶ Failure <tail>}. */
    public void finishPipelineFailure(String tail, List<String> above) {
        settle(PipelineWedge.failureLine(pipelineName(), nerdfont, tail), above);
    }

    /** {@link #finishPipelineFailure(String, List)} with no buffered output above. */
    public void finishPipelineFailure(String tail) {
        finishPipelineFailure(tail, List.of());
    }

    /**
     * Settle as a remote engine cancel ({@code jk cancel} / web): {@code Build job was cancelled
     * took …} — no "by user".
     */
    public void finishPipelineCancelled(List<String> above) {
        String took = cc.jumpkick.cli.run.ConsoleSpec.took(java.time.Duration.ofMillis(elapsedMillis()));
        settle(PipelineWedge.cancelledJobLine(pipelineName(), nerdfont, false, took), above);
    }

    /** {@link #finishPipelineCancelled(List)} with no buffered output above. */
    public void finishPipelineCancelled() {
        finishPipelineCancelled(List.of());
    }

    /**
     * Settle the build pipeline with the red chip, but a fully caller-composed sentence instead of the
     * "Failed to &lt;pipeline&gt;" derivation {@link #finishPipelineFailure} applies — see {@link
     * PipelineWedge#failureLineCustom}.
     */
    public void finishPipelineFailureCustom(String sentence, List<String> above) {
        settle(PipelineWedge.failureLineCustom(pipelineName(), nerdfont, sentence), above);
    }

    /** Settle with a red cross and a failure message. */
    public void finishFailure(String message) {
        finishFailure(message, List.of());
    }

    /** Like {@link #finishFailure(String)}, with buffered output printed above the result line. */
    public void finishFailure(String message, List<String> above) {
        settle(Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " " + message, above);
    }

    /**
     * Clear the live region without printing any result line — used when the pipeline's outcome is
     * communicated externally (e.g. via a post-pipeline chipLine printed by the caller). Same cleanup
     * as {@link #settle} but outputs nothing.
     */
    public void dismiss() {
        restoreStreams();
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            clearWindowTitle();
            if (animate) {
                if (pipelineMode) wipeRegion();
                else freezeSpinnerLine();
                out.print(Ansi.TASKBAR_CLEAR);
                out.print(Ansi.SHOW_CURSOR);
                out.flush();
            } else {
                out.flush();
            }
        }
    }

    /** The pipeline/command name shown in the header ("Building", "Locking", …). */
    private String pipelineName() {
        String n = pipelineMode ? name : label;
        return n == null ? "" : n;
    }

    private void settle(String line) {
        settle(line, List.of());
    }

    private void settle(String line, List<String> above) {
        restoreStreams(); // flush any captured output above the region first
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            clearWindowTitle();
            if (animate) {
                // Simple mode keeps the settled spinner line and prints the
                // result below it; pipeline mode replaces the whole region.
                if (pipelineMode) wipeRegion();
                else freezeSpinnerLine();
                out.print(Ansi.TASKBAR_CLEAR);
                out.print(Ansi.SHOW_CURSOR);
            }
            // Deferred subprocess output (e.g. compiler warnings) prints as
            // scrollback above the result line, with a blank separator, so the
            // settle line stays the last thing on screen.
            if (above != null && !above.isEmpty()) {
                for (String s : above) out.println(s);
                out.println();
            }
            out.println(line);
            out.flush();
        }
    }

    /** Cancel line text (shown by {@link GlobalCancel} when the region did not paint itself). */
    @Override
    public String canceledMessage() {
        return "Build job was cancelled";
    }

    @Override
    public boolean renderCanceled() {
        // Ctrl-C: hand the streams back so any buffered output flushes above the
        // region, stop animating, then settle. Pipeline mode replaces the wiped region
        // in place with the same cancelled-job wedge as a remote `jk cancel` / web cancel
        // ("✘ Build job was cancelled by user took …") and returns true so GlobalCancel
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
            if (pipelineMode) {
                wipeRegion();
                out.print(Ansi.TASKBAR_CLEAR);
                out.print(Ansi.SHOW_CURSOR);
                // Ctrl-C: "by user" + took duration.
                String took = cc.jumpkick.cli.run.ConsoleSpec.took(java.time.Duration.ofMillis(elapsedMillis()));
                out.println(PipelineWedge.cancelledJobLine(pipelineName(), nerdfont, true, took));
                out.flush();
                return true;
            }
            freezeSpinnerLine();
            out.print(Ansi.TASKBAR_CLEAR);
            out.print(Ansi.SHOW_CURSOR);
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
            wipeRegion();
            out.print(Ansi.TASKBAR_CLEAR);
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    /** Simple mode: repaint the spinner line with its first (settled) glyph, then newline. */
    private void freezeSpinnerLine() {
        out.print('\r');
        out.print(Theme.colorize(PULSE, openPulseColors[0]));
        out.print(' ');
        out.print(label);
        out.print(ELLIPSIS);
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print('\n');
    }

    /** Erase the live region and park the cursor at its top-left. */
    private void wipeRegion() {
        if (pipelineMode) {
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
            if (pipelineMode) paintPipeline();
            else paintSimple();
            // OSC title tracks the fill-circle phase (○→◎→◉→◎), not every chip-pulse frame.
            emitWindowTitleIfGlyphChanged();
            out.flush();
            // One counter; chip pulse and fill-circle use different period via floorMod.
            frame++;
        }
    }

    /** Repaint the single simple-mode spinner line in place (must hold {@link #lock}). */
    private void paintSimple() {
        out.print('\r');
        out.print(Theme.colorize(PULSE, openPulseColors[Math.floorMod(frame, openPulseColors.length)]));
        out.print(' ');
        out.print(label);
        out.print(ELLIPSIS);
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print(Ansi.TASKBAR_INDETERMINATE);
    }

    /**
     * Print {@code text} as a permanent line <em>above</em> the live region, then repaint the region
     * just below it — so process/step output scrolls up and the {@code CommandManager} view stays
     * pinned to the bottom. No-op-ish (plain {@code println}) when not animating or already settled.
     */
    public void writeAbove(String text) {
        synchronized (lock) {
            if (done || !animate) {
                out.println(text);
                out.flush();
                return;
            }
            // Erase the live region back to its top.
            if (pipelineMode) {
                if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
                out.print(Ansi.ERASE_DISPLAY_TO_END);
            } else {
                out.print(Ansi.CLEAR_LINE);
            }
            // Emit the text where the region's top was — it becomes scrollback.
            out.print(text);
            out.print('\n');
            // Repaint the region fresh, immediately below the emitted text.
            if (pipelineMode) {
                lastLines = List.of();
                linesDrawn = 0;
                paintPipeline();
            } else {
                paintSimple();
            }
            out.flush();
        }
    }

    /**
     * Repaint the multi-line pipeline region (must hold {@link #lock}), rewriting only the lines that
     * changed since the last paint to avoid flicker. The spinner header changes every frame; the bar
     * and step rows only on real updates, so a steady region mostly just rewrites its top line.
     *
     * <p>Cursor invariant: between paints the cursor is parked at the start of the line immediately
     * below the region. We move up to the first line, walk down rewriting changed lines (and
     * advancing past unchanged ones with a bare newline), then clear any lines a now-shorter region
     * left behind.
     */
    private void paintPipeline() {
        List<String> lines = renderPipelineLines(width, elapsedMillis());
        int prev = lastLines.size();
        if (prev > 0) out.print(Ansi.cursorUp(prev)); // to the top of the region
        for (int i = 0; i < lines.size(); i++) {
            boolean changed = i >= prev || !lines.get(i).equals(lastLines.get(i));
            if (changed) {
                out.print('\r');
                out.print(truncateVisible(lines.get(i), width));
                out.print(Ansi.ERASE_LINE_TO_END); // wipe any tail from a longer prior line
            }
            out.print('\n'); // advance to the next line / below region
        }
        // A shorter region than last time: erase the orphaned lines below.
        if (prev > lines.size()) out.print(Ansi.ERASE_DISPLAY_TO_END);
        out.print(Ansi.taskbarProgress(ProgressBar.percent(numerator, denominator)));
        lastLines = lines;
        linesDrawn = lines.size();
    }

    /**
     * Build the pipeline region's lines (header-with-bar, compact module/phase tree, completed
     * tail). Pure — no cursor control. Package-private for tests.
     *
     * <p>Tree (newest at top): only <em>running</em> and <em>failed</em> work — successful steps drop
     * out. Each row is {@code ├─ ● group:name · Phase · detail} with a blue pulse spinner while
     * running (no background pills). The trailing detail is the latest step {@link #stepMessage}
     * (test class, package sub-task, fetch artifact, …). Failed rows use a red cross and keep a
     * one-line brief under the branch. No blank spacer rails between rows — vertically compact.
     */
    public List<String> renderPipelineLines(int cols, long elapsedMillis) {
        AttributedStyle dim = Theme.active().darkGray();
        List<String> lines = new ArrayList<>();

        // 1. Header: pulse circle + name + bar + clock
        lines.add(pipelineHeader(elapsedMillis));

        // 2. Active work: prefer per-module step rows; fall back to phase-only (preflight).
        // Budget leaves the header line and one margin so the region stays inside the viewport.
        int budget = Math.max(1, height - 2);
        List<TreeEntry> visible = collectVisibleTree();
        int shown = 0;
        for (int i = 0; i < visible.size() && budget > 0; i++) {
            TreeEntry entry = visible.get(i);
            boolean last = i == visible.size() - 1;
            String branch = Theme.colorize(last ? " ╰─" : " ├─", dim);
            lines.add(branch + entry.line);
            budget--;
            shown++;
            if (entry.briefError != null && !entry.briefError.isEmpty() && budget > 0) {
                // Under ├─ continue the rail; under ╰─ use spaces (no dangling │) —.
                // Only the message is red; the rail/indent stays dim like the branch glyphs.
                lines.add(renderBriefErrorLine(last, entry.briefError));
                budget--;
            }
            if (shown >= MAX_ROWS) break;
        }

        // 3. Completed unit tail (newest first), if room remains
        if (completedCount > 0 && budget > 0) {
            boolean overflow = completedCount > Math.min(MAX_COMPLETIONS, budget);
            int cap = Math.max(0, Math.min(MAX_COMPLETIONS, overflow ? budget - 1 : budget));
            int compShown = Math.min(recentCompletions.size(), cap);
            int have = recentCompletions.size();
            for (int i = 0; i < compShown; i++) {
                lines.add("    " + recentCompletions.get(have - 1 - i));
            }
            int more = completedCount - compShown;
            if (more > 0) {
                lines.add(Theme.colorize("      … plus " + more + " more …", dim.italic()));
            }
        }
        return lines;
    }

    /** Running/failed tree entries, newest first. Module rows when available; else preflight phases. */
    private List<TreeEntry> collectVisibleTree() {
        List<TreeEntry> out = new ArrayList<>();
        List<Row> active = new ArrayList<>();
        List<Row> failed = new ArrayList<>();
        for (Row r : rows.values()) {
            if (r.state == RowState.ACTIVE) active.add(r);
            else if (r.state == RowState.FAILED) failed.add(r);
        }
        if (!active.isEmpty() || !failed.isEmpty()) {
            // LinkedHashMap insert order → reverse so newest work floats up.
            for (int i = active.size() - 1; i >= 0; i--) out.add(treeEntryForRow(active.get(i)));
            failed.sort((a, b) -> Long.compare(b.seq, a.seq));
            for (Row r : failed) out.add(treeEntryForRow(r));
            return out;
        }
        // Preflight (or any phase with no step rows yet): phase-only labels.
        for (int i = phaseOrder.size() - 1; i >= 0; i--) {
            PhaseNode n = phases.get(phaseOrder.get(i));
            if (n != null && (n.state == PhaseState.RUNNING || n.state == PhaseState.FAILED)) {
                out.add(treeEntryForPhase(n));
            }
        }
        return out;
    }

    private TreeEntry treeEntryForRow(Row r) {
        boolean failed = r.state == RowState.FAILED;
        String brief = failed ? r.briefError : "";
        if ((brief == null || brief.isEmpty()) && failed) {
            PhaseNode n = phases.get(r.phase);
            if (n != null) brief = n.briefError;
        }
        // Live detail only on running rows — failed rows use the brief under the branch.
        String detail = failed ? "" : detailForDisplay(r.module, r.message);
        return new TreeEntry(renderWorkRow(r.module, phaseLabel(r.phase), failed, detail), brief == null ? "" : brief);
    }

    private TreeEntry treeEntryForPhase(PhaseNode n) {
        boolean failed = n.state == PhaseState.FAILED;
        String label = n.label == null || n.label.isEmpty() ? phaseLabel(n.key) : n.label;
        String detail = failed ? "" : (n.detail == null ? "" : n.detail);
        return new TreeEntry(renderWorkRow("", label, failed, detail), failed ? n.briefError : "");
    }

    /**
     * Step label for the tree detail segment. Strips a leading {@code module:: } prefix when the
     * engine label already embeds the coordinate (test progress labels) so the row does not read
     * {@code g:a · Test · g:a:: FooTest}.
     */
    static String detailForDisplay(String module, String message) {
        if (message == null || message.isBlank()) return "";
        String msg = message.trim();
        String mod = module == null ? "" : module.trim();
        if (!mod.isEmpty() && msg.startsWith(mod + " :: ")) {
            msg = msg.substring(mod.length() + 4).trim();
        }
        return msg;
    }

    /**
     * Brief under a failed tree row: dim rail/indent, red message only (not the whole line).
     *
     * @param last whether this is the last tree entry (closing branch → space indent)
     */
    static String renderBriefErrorLine(boolean last, String brief) {
        String errIndent = last ? "    " : " │  ";
        Theme t = Theme.active();
        return Theme.colorize(errIndent, t.darkGray()) + Theme.colorize(brief == null ? "" : brief, t.error());
    }

    /**
     * One tree body: {@code ● group:name · Phase · detail} (or {@code ● Phase} with no module).
     * Running uses a blue pulse spinner with no background; failed uses a red cross; phase label is
     * green or red. Detail text is phase-aware (see {@link #colorDetail}): gray by default, Java
     * syntax for tests, path color for artifacts, blue counts for compile. Lines never wrap — the
     * paint path hard-truncates to the terminal width.
     */
    private String renderWorkRow(String module, String displayPhase, boolean failed, String detail) {
        Theme t = Theme.active();
        String icon;
        AttributedStyle phaseStyle;
        if (failed) {
            icon = Theme.colorize(Glyphs.CROSS, t.error());
            phaseStyle = t.error();
        } else {
            // Filling circle (○→◎→◉→◎) in constant blue — not the CommandWedge color-pulse ●.
            icon = Theme.colorize(Spinner.fillGlyph(frame), t.blue());
            phaseStyle = t.success();
        }
        String phase = displayPhase == null || displayPhase.isEmpty() ? "?" : displayPhase;
        StringBuilder sb = new StringBuilder();
        sb.append(' ').append(icon).append(' ');
        if (module != null && !module.isEmpty()) {
            sb.append(coloredModule(module))
                    .append(' ')
                    .append(Theme.colorize("·", t.darkGray()))
                    .append(' ');
        }
        sb.append(Theme.colorize(phase, phaseStyle));
        if (detail != null && !detail.isBlank()) {
            sb.append(' ').append(Theme.colorize("·", t.darkGray())).append(' ').append(colorDetail(phase, detail, t));
        }
        return sb.toString();
    }

    /**
     * Color a live step detail under the phase label.
     *
     * <ul>
     * <li><b>{@code Class.method(…)} form only</b> (run-tests live labels): Java {@link
     * cc.jumpkick.cli.run.SyntaxHighlight} — not every step under the Test phase (compile-test
     * is phase Test too and must stay prose gray)
     * <li><b>Everything else</b>: prose in mid-gray ({@link Theme#midGray} {@code #A0A0A0}), never
     * cyan and never dim bright-black, with:
     * <ul>
     * <li>integers / counts / sizes → blue ({@link Theme#synNumber})
     * <li>size units ({@code MiB}, {@code KB}, …) stay gray after the number
     * <li>artifact filenames and path-like tokens → {@link Theme#path}
     * <li>Maven {@code group:artifact(:version)} → {@link cc.jumpkick.cli.theme.Coords}
     * <li>fetched short-names / bare library ids after resolve verbs → coord short-name
     * <li>short cache key hex → dimmest gray
     * </ul>
     * <li>Trailing {@code [wN]} worker tags stay gray
     * </ul>
     */
    static String colorDetail(String phase, String detail, Theme t) {
        if (detail == null || detail.isBlank()) return "";
        String body = detail;
        String worker = "";
        // progressLabel appends " [w2]" — keep it outside the Java highlighter.
        int w = detail.lastIndexOf("  [w");
        if (w > 0 && detail.endsWith("]")) {
            body = detail.substring(0, w);
            worker = detail.substring(w);
        }
        // Only syntax-highlight true member refs (FooTest.bar). Phase "Test" also hosts
        // compile-test labels like "compiling 12 sources" — those must stay mid-gray prose
        // (SyntaxHighlight paints unmatched text as terminal default/white).
        String painted = looksLikeJavaMember(body)
                ? cc.jumpkick.cli.run.SyntaxHighlight.highlight(body, -1)
                : colorProseDetail(body, t);
        if (worker.isEmpty()) return painted;
        return painted + Theme.colorize(worker, t.midGray());
    }

    /**
     * Free-text step labels: mid-gray ({@code #A0A0A0}) prose with numbers, paths, coordinates, and
     * fetch names picked out. Never uses cyan for body text (reserved for {@code group:artifact} on
     * the module segment) and never uses dim bright-black ({@link Theme#darkGray}) for default
     * prose.
     */
    static String colorProseDetail(String text, Theme t) {
        if (text == null || text.isEmpty()) return "";
        AttributedStyle gray = t.midGray(); // #A0A0A0 — ordinary gray, not dim chrome
        AttributedStyle number = t.synNumber();
        AttributedStyle path = t.path();
        AttributedStyle hash = t.darkGray(); // slightly dimmer than body — cache key hex
        StringBuilder out = new StringBuilder(text.length() + 64);
        int i = 0;
        int n = text.length();
        // Track the previous word (lowercase) so "fetched foo" / "resolve bar" can tint the name.
        String prevWord = "";
        while (i < n) {
            char c = text.charAt(i);
            // Skip whitespace as gray, then continue.
            if (Character.isWhitespace(c)) {
                int j = i + 1;
                while (j < n && Character.isWhitespace(text.charAt(j))) j++;
                out.append(Theme.colorize(text.substring(i, j), gray));
                i = j;
                continue;
            }

            // Lone punctuation (parens, arrows, …) so "(12 classes)" still blues the 12.
            if (!Character.isLetterOrDigit(c)
                    && c != '_'
                    && c != '-'
                    && c != '.'
                    && c != '/'
                    && c != '\\'
                    && c != '~'
                    && c != ':') {
                out.append(Theme.colorize(String.valueOf(c), gray));
                // Don't reset prevWord on '(' so "fetched (jackson-core)" still works.
                if (c != '(' && c != '[') prevWord = "";
                i++;
                continue;
            }

            // Pull the next non-whitespace token (may include: /. for coords & paths).
            int j = scanTokenEnd(text, i);
            int end = j;
            while (end > i && isTrailingPunct(text.charAt(end - 1))) end--;
            String tok = text.substring(i, end);
            String trail = text.substring(end, j); // trailing,); etc.

            // 1. Maven coordinate — group:artifact or GAV.
            if (looksLikeCoord(tok)) {
                out.append(colorCoord(tok));
                if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
                prevWord = "";
                i = j;
                continue;
            }

            // 2. Path / artifact file.
            if (looksLikePathOrArtifact(tok)) {
                out.append(Theme.colorize(tok, path));
                if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
                prevWord = "";
                i = j;
                continue;
            }

            // 3. Number (count or size). Hex cache keys prefer dim gray.
            if (Character.isDigit(c)) {
                int hexEnd = i;
                while (hexEnd < n && isHex(text.charAt(hexEnd))) hexEnd++;
                if (hexEnd - i >= 8 && (hexEnd >= n || !Character.isLetterOrDigit(text.charAt(hexEnd)))) {
                    out.append(Theme.colorize(text.substring(i, hexEnd), hash));
                    prevWord = "";
                    i = hexEnd;
                    continue;
                }
                int k = i;
                while (k < n && Character.isDigit(text.charAt(k))) k++;
                if (k < n && text.charAt(k) == '.' && k + 1 < n && Character.isDigit(text.charAt(k + 1))) {
                    k++;
                    while (k < n && Character.isDigit(text.charAt(k))) k++;
                }
                out.append(Theme.colorize(text.substring(i, k), number));
                // Optional size unit immediately after (or after one space): MiB, KB, …
                int u = k;
                if (u < n && text.charAt(u) == ' ') {
                    int uEnd = scanTokenEnd(text, u + 1);
                    String unit = text.substring(u + 1, uEnd);
                    if (looksLikeSizeUnit(unit)) {
                        out.append(Theme.colorize(" ", gray));
                        out.append(Theme.colorize(unit, gray));
                        i = uEnd;
                        prevWord = "";
                        continue;
                    }
                } else if (u < n && Character.isLetter(text.charAt(u))) {
                    int uEnd = scanTokenEnd(text, u);
                    String unit = text.substring(u, uEnd);
                    if (looksLikeSizeUnit(unit)) {
                        out.append(Theme.colorize(unit, gray));
                        i = uEnd;
                        prevWord = "";
                        continue;
                    }
                }
                prevWord = "";
                i = k;
                continue;
            }

            // 4. After resolve/fetch verbs, tint bare library / package short-names.
            if (isFetchOrResolveVerb(prevWord) && looksLikeLibraryShortName(tok)) {
                out.append(cc.jumpkick.cli.theme.Coords.shortName(tok));
                if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
                prevWord = tok.toLowerCase(java.util.Locale.ROOT);
                i = j;
                continue;
            }

            // 5. Plain gray word (and remember it for verb context).
            out.append(Theme.colorize(tok, gray));
            if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
            prevWord = tok.toLowerCase(java.util.Locale.ROOT);
            i = j;
        }
        return out.toString();
    }

    /** End index of the token starting at {@code i} (exclusive). Stops at whitespace. */
    private static int scanTokenEnd(String text, int i) {
        int n = text.length();
        int j = i;
        while (j < n && !Character.isWhitespace(text.charAt(j))) j++;
        return j;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isTrailingPunct(char c) {
        return c == ',' || c == ')' || c == ';' || c == '(' || c == '[' || c == ']';
    }

    /** {@code MiB}, {@code KB}, {@code ms}, … — stay gray after a blue number. */
    static boolean looksLikeSizeUnit(String unit) {
        if (unit == null || unit.isEmpty()) return false;
        return switch (unit) {
            case "B",
                    "K",
                    "M",
                    "G",
                    "T",
                    "KB",
                    "MB",
                    "GB",
                    "TB",
                    "KiB",
                    "MiB",
                    "GiB",
                    "TiB",
                    "kb",
                    "mb",
                    "gb",
                    "kib",
                    "mib",
                    "gib",
                    "ms",
                    "s",
                    "m",
                    "h",
                    "files",
                    "file",
                    "sources",
                    "source",
                    "tests",
                    "test",
                    "jars",
                    "jar",
                    "classes",
                    "inputs",
                    "input" -> true;
            default -> false;
        };
    }

    /** Verbs whose following token is often a library / package id. */
    static boolean isFetchOrResolveVerb(String word) {
        if (word == null || word.isEmpty()) return false;
        return switch (word) {
            case "fetched",
                    "fetch",
                    "fetching",
                    "resolve",
                    "resolving",
                    "resolved",
                    "download",
                    "downloading",
                    "downloaded",
                    "install",
                    "installing",
                    "installed",
                    "load",
                    "loading",
                    "loaded",
                    "pushing",
                    "pushed",
                    "pulling",
                    "pulled" -> true;
            default -> false;
        };
    }

    /**
     * Bare library short-name ({@code jackson-core}, {@code junit}) — not prose, not a path, not a
     * pure number.
     */
    static boolean looksLikeLibraryShortName(String tok) {
        if (tok == null || tok.length() < 2) return false;
        if (looksLikePathOrArtifact(tok) || looksLikeCoord(tok)) return false;
        // Must start with a letter; allow letters, digits, dots, hyphens, underscores.
        char c0 = tok.charAt(0);
        if (!Character.isLetter(c0)) return false;
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) return false;
        }
        // Reject common English words that follow "resolve" in prose.
        return switch (tok.toLowerCase(java.util.Locale.ROOT)) {
            case "deps",
                    "dependencies",
                    "classpath",
                    "jdk",
                    "java",
                    "sources",
                    "tests",
                    "resources",
                    "plugins",
                    "plugin",
                    "modules",
                    "module",
                    "lock",
                    "cache",
                    "the",
                    "a",
                    "an",
                    "to",
                    "for",
                    "from",
                    "with",
                    "and",
                    "or",
                    "of",
                    "in",
                    "on",
                    "via",
                    "no",
                    "up",
                    "date",
                    "hit",
                    "miss" -> false;
            default -> true;
        };
    }

    /**
     * Maven-style {@code group:artifact} or {@code group:artifact:version} (optionally with
     * classifier/extension segments). Requires at least one {@code ':'} and no whitespace.
     */
    static boolean looksLikeCoord(String tok) {
        if (tok == null || tok.isEmpty()) return false;
        int first = tok.indexOf(':');
        if (first <= 0 || first == tok.length() - 1) return false;
        if (tok.indexOf('/') >= 0 || tok.indexOf('\\') >= 0) return false; // paths win
        String[] parts = tok.split(":", -1);
        if (parts.length < 2 || parts.length > 5) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) return false;
            }
        }
        // group usually has a dot (reverse-DNS) OR artifact has a hyphen/common form.
        return parts[0].indexOf('.') >= 0 || parts[1].indexOf('-') >= 0 || parts[1].length() >= 2;
    }

    /** Paint {@code g:a} / {@code g:a:v} with the same colors as dependency trees. */
    static String colorCoord(String tok) {
        String[] parts = tok.split(":", -1);
        if (parts.length == 2) return cc.jumpkick.cli.theme.Coords.ga(parts[0], parts[1]);
        if (parts.length >= 3) {
            // group:artifact:version — extra segments (classifier) stay on the version color.
            StringBuilder ver = new StringBuilder(parts[2]);
            for (int i = 3; i < parts.length; i++) ver.append(':').append(parts[i]);
            return cc.jumpkick.cli.theme.Coords.gav(parts[0], parts[1], ver.toString());
        }
        return Theme.colorize(tok, Theme.active().midGray());
    }

    /** {@code lib.jar}, {@code app.aar}, absolute/relative paths — not ordinary prose words. */
    static boolean looksLikePathOrArtifact(String tok) {
        if (tok == null || tok.isEmpty()) return false;
        if (tok.indexOf('/') >= 0 || tok.indexOf('\\') >= 0) return true;
        if (tok.startsWith("~")) return true;
        int dot = tok.lastIndexOf('.');
        if (dot <= 0 || dot == tok.length() - 1) return false;
        String ext = tok.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "jar",
                    "aar",
                    "apk",
                    "aab",
                    "war",
                    "ear",
                    "zip",
                    "tar",
                    "gz",
                    "tgz",
                    "properties",
                    "toml",
                    "xml",
                    "json",
                    "so",
                    "dylib",
                    "dll",
                    "exe",
                    "class",
                    "java",
                    "kt",
                    "kts",
                    "groovy" -> true;
            default -> false;
        };
    }

    /**
     * Capitalized type, optional {@code .method(…)}, no spaces (worker tags already stripped).
     * Compiled once: this runs per visible row on every 80 ms animator frame — with a 128 MB
     * heap, per-frame {@code String.matches} (a fresh {@code Pattern.compile}) is real garbage.
     */
    private static final java.util.regex.Pattern JAVA_MEMBER =
            java.util.regex.Pattern.compile("[A-Z][\\w$]*(?:\\.[A-Za-z_][\\w$]*(?:\\([^)]*\\))?)?");

    /** {@code FooTest}, {@code FooTest.bar()}, or {@code FooTest.bar(Path)} — not free text. */
    static boolean looksLikeJavaMember(String s) {
        if (s == null || s.isEmpty()) return false;
        return JAVA_MEMBER.matcher(s).matches();
    }

    /**
     * Pipeline header: pulse circle + name on the chip, powerline (or plain) cap, bar, clock.
     * The circle FG breathes white↔chip-blue while sitting on the chip background.
     */
    private String pipelineHeader(long elapsedMillis) {
        Theme t = Theme.active();
        AttributedStyle dim = t.darkGray();
        String barStr = bar.render(numerator, denominator);
        StringBuilder h = new StringBuilder();
        String sl = solveLabel;
        boolean phase1 = denominator == 0 && !sl.isEmpty();
        AttributedStyle chip = t.pipelineChip();
        // Pulse glyph: FG lerps white→chip blue; BG stays chip blue so it sits in the pill.
        AttributedStyle pulse =
                t.withBackground(chipPulseColors[Math.floorMod(frame, chipPulseColors.length)], t.planBadgeColor());
        if (nerdfont) {
            h.append(Theme.colorize(" ", chip))
                    .append(Theme.colorize(PULSE, pulse))
                    .append(Theme.colorize(" ", chip))
                    .append(Theme.colorize(name, chip))
                    .append(Theme.colorize(" ", chip));
            if (phase1) {
                AttributedStyle phase1Cap = t.bright(t.planBadgeColor());
                h.append(Theme.colorize(Glyphs.SEGMENT_END_NERD, phase1Cap))
                        .append(' ')
                        .append(Theme.colorize(sl, t.brightWhite()));
            } else {
                AttributedStyle cap =
                        t.withBackground(t.bright(t.planBadgeColor()), bar.leadColor(numerator, denominator));
                h.append(Theme.colorize(Glyphs.SEGMENT_END_NERD, cap)).append(barStr);
            }
        } else {
            h.append(Theme.colorize(" ", chip))
                    .append(Theme.colorize(PULSE, pulse))
                    .append(Theme.colorize(" " + name + " ", chip))
                    .append(Theme.colorize(" ", chip)); // plain trailing cap space
            if (phase1) {
                h.append(' ').append(Theme.colorize(sl, t.brightWhite()));
            } else {
                h.append(barStr);
            }
        }
        // After the bar's percent: a bright-black middle dot, then the run-wide build clock.
        // Seeded estimate → pure wall-clock countdown (blue) then +Ns overrun (yellow); no seed
        // → +Ns count-up from construction (yellow). Never resets on phase/module boundaries.
        String clockStr;
        boolean countUp;
        if (etaEstimateMs > 0) {
            long remaining = etaEstimateMs - elapsedMillis;
            countUp = remaining <= 0;
            clockStr = countUp ? "+" + fmtClock(-remaining) : fmtClock(remaining);
        } else {
            countUp = true;
            clockStr = "+" + fmtClock(elapsedMillis);
        }
        AttributedStyle clockStyle = countUp ? t.warning() : t.blue();
        h.append(' ').append(Theme.colorize("·", dim)).append(' ').append(Theme.colorize(clockStr, clockStyle));
        // remaining-work module counter (run-wide, not per-module local).
        if (modulesTotal > 0) {
            String mods = modulesComplete + "/" + modulesTotal;
            h.append(' ').append(Theme.colorize("·", dim)).append(' ').append(Theme.colorize(mods, dim));
        }
        return h.toString();
    }

    /**
     * Countdown/elapsed duration: {@code "42s"}, {@code "1m 02s"}, {@code "1h 05m 09s"} (units past
     * the lead zero-padded). Callers prepend {@code "+"} for count-up display.
     */
    static String fmtClock(long millis) {
        long totalSec = Math.max(0, millis) / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m " + String.format("%02d", s) + "s";
        if (m > 0) return m + "m " + String.format("%02d", s) + "s";
        return s + "s";
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
        if (wire == null || wire.isEmpty()) return "?";
        return Character.toUpperCase(wire.charAt(0)) + wire.substring(1);
    }

    /**
     * {@code group:artifact} → cyan group + bold bright-cyan artifact (pipeline tree / failure
     * tails). Plain settled style if no colon.
     */
    public static String coloredModule(String module) {
        int colon = module.indexOf(':');
        if (colon < 0) return Theme.colorize(module, Theme.active().settled());
        return Theme.colorize(module.substring(0, colon), Theme.active().cyan())
                + ":"
                + Theme.colorize(
                        module.substring(colon + 1), Theme.active().brightCyan().bold());
    }

    private long elapsedMillis() {
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

    private void stopAnimator() {
        stopped = true;
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

    // --- helpers ----------------------------------------------------------

    private static String key(String module, String stepKey) {
        return module + '\0' + stepKey;
    }

    /** "compile-java" → "Compile java"; "runTests" → "RunTests" (best-effort). */
    static String humanize(String stepKey) {
        if (stepKey == null || stepKey.isEmpty()) return "";
        String spaced = stepKey.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** "(1m 52s)" content: minutes+seconds past a minute, else just seconds. */
    static String fmtElapsed(long millis) {
        long totalSec = Math.max(0, millis) / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    /**
     * Truncate an ANSI-colored string to {@code maxCols} visible columns, copying escape sequences
     * without counting them and appending a reset if the text was cut. Treats every visible codepoint
     * as one column (good enough for our ASCII + single-width glyphs).
     *
     * <p>JLine can do this width-aware ({@code AttributedString.fromAnsi} / {@code WCWidth}), but
     * measured at +187–312 KB on the native image — its ANSI parser / width tables aren't otherwise
     * reachable — to gain East-Asian wide-glyph handling that jk's ASCII coordinates and single-width
     * box/spinner glyphs never need. Not worth the binary growth, so this stays hand-rolled by
     * design.
     */
    /**
     * Hard-truncate to {@code maxCols} visible columns (never wraps). When cut, ends with {@code …}
     * so long test member names stay on one line.
     */
    static String truncateVisible(String s, int maxCols) {
        if (maxCols <= 0) return "";
        // No maxCols==1 shortcut: the reserve logic below already handles it — a 1-column
        // string fits as-is, only longer input degrades to the bare ellipsis.
        int budget = maxCols;
        StringBuilder sb = new StringBuilder(s.length());
        int visible = 0;
        boolean truncated = false;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\033') { // copy the whole CSI sequence verbatim
                int j = i + 1;
                if (j < s.length() && s.charAt(j) == '[') {
                    j++;
                    while (j < s.length() && !Character.isLetter(s.charAt(j))) j++;
                    if (j < s.length()) j++; // include the final letter
                }
                sb.append(s, i, j);
                i = j;
            } else {
                // Reserve one column for … when more content remains.
                boolean moreAfter = i + 1 < s.length() && !isOnlyAnsiFrom(s, i + 1);
                int need = moreAfter ? 1 : 0; // room for ellipsis
                if (visible + 1 + need > budget) {
                    truncated = true;
                    break;
                }
                sb.append(c);
                visible++;
                i++;
            }
        }
        if (truncated) {
            sb.append(ELLIPSIS);
            sb.append(Ansi.RESET);
        }
        return sb.toString();
    }

    /** True when {@code s[from..]} is only ANSI escapes (no more visible text). */
    private static boolean isOnlyAnsiFrom(String s, int from) {
        for (int i = from; i < s.length(); ) {
            char c = s.charAt(i);
            if (c != '\033') return false;
            int j = i + 1;
            if (j < s.length() && s.charAt(j) == '[') {
                j++;
                while (j < s.length() && !Character.isLetter(s.charAt(j))) j++;
                if (j < s.length()) j++;
            }
            i = j;
        }
        return true;
    }

    /**
     * Terminal size {@code {rows, cols}}, detected once, leak-free. We deliberately do NOT build a
     * JLine terminal: JLine probes the terminal with capability queries (DA1 {@code \e[c}, mode
     * reports like {@code \e[?2027$p}), and a transient build-then-close races the async replies
     * they arrive after we exit and the shell echoes them as garbage. Instead ask the tty directly
     * via {@code stty size} (an ioctl, no escape sequences), then the {@code $LINES}/{@code $COLUMNS}
     * env, then conservative defaults. Only called when animating (interactive tty).
     */
    /** Terminal width in columns ({@code stty size} → {@code $COLUMNS} → {@value #DEFAULT_WIDTH}). */
    public static int detectColumns() {
        return detectSize()[1];
    }

    private static int[] detectSize() {
        try {
            Process p = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/tty")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out =
                    new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
            p.waitFor();
            String[] parts = out.split("\\s+"); // "<rows> <cols>"
            if (parts.length == 2) {
                int rows = Integer.parseInt(parts[0]);
                int cols = Integer.parseInt(parts[1]);
                if (rows > 0 && cols > 0) return new int[] {rows, cols};
            }
        } catch (Exception ignored) {
            // no /dev/tty, no stty (e.g. Windows), or unparsable — fall through
        }
        return new int[] {envInt("LINES", DEFAULT_HEIGHT), envInt("COLUMNS", DEFAULT_WIDTH)};
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            }
        } catch (NumberFormatException ignored) {
            // not a number — use the fallback
        }
        return fallback;
    }

    /** Restores {@code System.out}/{@code System.err} when closed (no checked exception). */
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
     * region settles (so a Ctrl-C mid-pipeline hands the streams back before {@link GlobalCancel}
     * prints).
     */
    private void restoreStreams() {
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
    private static final class LineSink extends OutputStream {
        private final CommandManager cm;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long lastWriteNanos; // when the current partial line last grew

        LineSink(CommandManager cm) {
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
            cm.writeAbove(s);
        }
    }

    private enum RowState {
        PENDING,
        ACTIVE,
        DONE,
        FAILED
    }

    private enum PhaseState {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    private static final class PhaseNode {
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

    private static final class Row {
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
    private static final class TreeEntry {
        final String line;
        final String briefError;

        TreeEntry(String line, String briefError) {
            this.line = line;
            this.briefError = briefError == null ? "" : briefError;
        }
    }
}
