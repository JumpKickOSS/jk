// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.NullMarked;

/** Finish / plain-chrome / plan-paint collaborator for {@link JkManager}. */
@NullMarked
final class JkManagerView {

    private final JkManager m;

    /**
     * When true, the next {@link #paintBuildPlan()} rewrites every row even if content is unchanged
     * (terminal resize changed the truncation budget).
     */
    private boolean forceFullRepaint;

    /**
     * Terminal columns used for the last paint. On a shrink, already-drawn lines may reflow to more
     * physical rows than {@code lastLines.size()}; wipe uses this to estimate how far to cursor-up.
     */
    private int paintedCols;

    JkManagerView(JkManager m) {
        this.m = m;
        this.paintedCols = m.width;
    }

    /** Request a full rewrite on the next paint (Ctrl-O toggle, force-show, resize). */
    void requestFullRepaint() {
        forceFullRepaint = true;
    }

    // --- completion -------------------------------------------------------

    /** Settle with {@code ✔ <plan> Successful: <message>} (the head in green). */
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
        String head = Glyphs.CHECK + (m.planName().isEmpty() ? "" : " " + m.planName()) + " Successful";
        m.settle(Theme.colorize(head, Theme.active().success()) + ": " + message, above);
    }

    /**
     * Settle the build plan with the green chip: {@code ✓ Build ▶ Successfully <tail>}. The {@code
     * tail} (e.g. "built 17 modules took 1.4s") is pre-styled by the caller; this owns only the chip
     * + cap + command. See {@link JkWedge}.
     */
    public void finishBuildPlanSuccess(String tail, List<String> above) {
        m.settle(JkWedge.ok(m.planName(), tail).renderLine(m.headerContext()), above);
    }

    /** {@link #finishBuildPlanSuccess(String, List)} with no buffered output above. */
    public void finishBuildPlanSuccess(String tail) {
        finishBuildPlanSuccess(tail, List.of());
    }

    /**
     * Settle with the play chip: {@code ▶ Run Executing `java …`} — for commands that hand off to a
     * subprocess after the plan settles (e.g. {@code jk run}). {@code planName} is the
     * command label (typically {@code Run}); {@code tail} is the pre-styled message.
     *
     * <p>{@code jk run} prints its own single separator before {@code inheritIO} (no settle
     * trailing blank — settles never add one; see {@link JkManager#settle}).
     */
    public void finishBuildPlanExec(String tail, List<String> above) {
        m.settle(JkWedge.work(m.planName(), tail).renderLine(m.headerContext()), above);
    }

    /** {@link #finishBuildPlanExec(String, List)} with no buffered output above. */
    public void finishBuildPlanExec(String tail) {
        finishBuildPlanExec(tail, List.of());
    }

    /** Settle the build plan with the red chip: {@code ‼ Build ▶ Failure <tail>}. */
    public void finishBuildPlanFailure(String tail, List<String> above) {
        m.settle(JkWedge.failedTo(m.planName(), tail).renderLine(m.headerContext()), above);
    }

    /** {@link #finishBuildPlanFailure(String, List)} with no buffered output above. */
    public void finishBuildPlanFailure(String tail) {
        finishBuildPlanFailure(tail, List.of());
    }

    /**
     * Settle as a remote engine cancel ({@code jk cancel} / web): gray {@code ⊛ Build} chip, then
     * {@code job was cancelled took …} — no "by user".
     */
    public void finishBuildPlanCancelled(List<String> above) {
        String took = cc.jumpkick.cli.run.ConsoleSpec.took(Duration.ofMillis(m.elapsedMillis()));
        m.settle(JkWedge.cancelled(m.planName(), false, took).renderLine(m.headerContext()), above);
    }

    /** {@link #finishBuildPlanCancelled(List)} with no buffered output above. */
    public void finishBuildPlanCancelled() {
        finishBuildPlanCancelled(List.of());
    }

    /**
     * Settle the build plan with the red chip, but a fully caller-composed sentence instead of the
     * "Failed to &lt;plan&gt;" derivation {@link #finishBuildPlanFailure} applies — see {@link
     * JkWedge#failedTo}.
     */
    public void finishBuildPlanFailureCustom(String sentence, List<String> above) {
        m.settle(JkWedge.fail(m.planName(), RichText.ansi(sentence)).renderLine(m.headerContext()), above);
    }

    /** Settle with a red cross and a failure message. */
    public void finishFailure(String message) {
        finishFailure(message, List.of());
    }

    /** Like {@link #finishFailure(String)}, with buffered output printed above the result line. */
    public void finishFailure(String message, List<String> above) {
        m.settle(Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " " + message, above);
    }

    /**
     * Clear the live region without printing any result line — used when the plan's outcome is
     * communicated externally (e.g. via a post-plan chipLine printed by the caller). Same cleanup
     * as {@link JkManager#settle} but outputs nothing.
     */
    public void dismiss() {
        m.restoreStreams();
        m.stopAnimator();
        synchronized (m.lock) {
            if (m.done) return;
            m.done = true;
            LiveRegion.clearActive(m);
            m.clearWindowTitle();
            if (m.animate && Theme.active().isAnsi()) {
                if (m.planMode) m.wipeRegion();
                else m.freezeSpinnerLine();
                m.out.print(Ansi.taskbarClear());
                m.out.print(Ansi.SHOW_CURSOR);
                m.out.flush();
            } else if (m.animate && !Theme.active().isAnsi()) {
                // Plain: end multi-line chrome without a settle wedge (caller owns outcome).
                m.printPlainDone();
            } else {
                m.out.flush();
            }
        }
    }

    /** The plan/command name shown in the header ("Building", "Locking", …). */
    String planName() {
        String n = m.planMode ? m.name : m.label;
        return n == null ? "" : n;
    }

    void settle(String line) {
        m.settle(line, List.of());
    }

    /**
     * Print the settled result line. One blank before chrome starts (envelope). After committed
     * process output, one blank between that scrollback and the settle chip. No blank after the
     * settle line (would look like an extra row before the shell prompt). Callers that hand off to
     * a subprocess ({@code jk run}) add their own separator when needed.
     */
    void settle(String line, List<String> above) {
        m.restoreStreams(); // flush any captured output above the region first
        m.stopAnimator();
        synchronized (m.lock) {
            if (m.done) return;
            m.done = true;
            LiveRegion.clearActive(m);
            m.clearWindowTitle();
            // Process lines already in scrollback; wipe removes rule/blank + live chrome.
            int processAbove = m.planMode ? m.outputWindow.committedScrollbackLines() : 0;
            if (m.animate && Theme.active().isAnsi()) {
                if (m.planMode) m.flushVisibleOutputToScrollback();
                // Simple mode keeps the settled spinner line and prints the result below it; plan
                // mode replaces the whole region (cursor lands on the first wiped row).
                if (m.planMode) m.wipeRegion();
                else m.freezeSpinnerLine();
                m.out.print(Ansi.taskbarClear());
                m.out.print(Ansi.SHOW_CURSOR);
            } else if (m.animate && !Theme.active().isAnsi()) {
                // Plain multi-line: mandatory done line before the settle wedge.
                m.printPlainDone();
            }
            // Deferred subprocess output (e.g. compiler warnings) prints as
            // scrollback above the result line, with a blank separator, so the
            // settle line stays the last thing on screen.
            boolean printedAbove = false;
            if (above != null && !above.isEmpty()) {
                for (String s : above) {
                    if (s == null || s.isBlank()) continue;
                    m.out.println(s);
                    printedAbove = true;
                }
            }
            // Exactly one blank between external output (process scrollback and/or deferred
            // above) and the settle chip. Do not stack two blanks when both are present.
            if (printedAbove || processAbove > 0) {
                m.out.println();
            }
            m.ensureLeadingBlank(); // quiet / late m.settle still gets the leading blank
            m.out.println(line);
            m.out.flush();
            m.outputWindow.resetCommitted();
        }
    }

    // --- plain multi-line chrome ---------------------------------

    /**
     * Emit plain progress lines for every newly crossed 20% step up to (and not past) 80%.
     * Must hold the manager lock. First call prints {@code initializing...} then the 0% prepare
     * line.
     */
    void emitPlainProgressDecades(long num, long den) {
        if (m.done || den <= 0) return;
        m.plainProgressMode = true;
        maybePrintPlainInitializing();
        int decade = new Progress(num, den).plainDecade();
        if (m.plainLastDecade < 0) {
            String status = currentPlainStatus();
            String subject = currentPlainSubject();
            if (PlainPhase.PREPARE.equals(status) || firstActiveRow() == null) {
                status = PlainPhase.PREPARE;
                if (subject.isEmpty()) subject = m.planCoord == null ? "" : m.planCoord;
            }
            printPlainSnapshot(0, status, false, subject, "");
            m.plainLastDecade = 0;
            return;
        }
        while (m.plainLastDecade < decade) {
            m.plainLastDecade += Progress.PLAIN_STEP_PERCENT;
            printPlainSnapshot(m.plainLastDecade, currentPlainStatus(), false);
        }
    }

    /** First ETA seed: print immediately as {@code start} on the workspace coordinate. */
    void emitPlainEtaKnown() {
        if (m.done || !m.animate) return;
        if (m.plainEtaAnnounced) return;
        m.plainProgressMode = true;
        maybePrintPlainInitializing();
        String root = planCoordOrEmpty();
        if (!PlainPhase.PREPARE.equals(m.plainLastStatus) && firstActiveRow() == null && !root.isEmpty()) {
            printPlainSnapshot(0, PlainPhase.PREPARE, false, root, "");
        }
        m.plainEtaAnnounced = true;
        printPlainSnapshot(currentPlainPercent(), PlainPhase.START, false, root, "");
    }

    /** Major step/phase change: print immediately at the current percent. */
    void emitPlainPhaseChange() {
        if (m.done || !m.animate) return;
        m.plainProgressMode = true;
        maybePrintPlainInitializing();
        if (m.remainingWorkMs >= 0) m.plainEtaAnnounced = true;
        String status = currentPlainStatus();
        if (PlainPhase.PREPARE.equals(status)) return;
        // Wait for "compiling N sources" / "running N tests" before the first line of those phases.
        if ("compiling".equals(status) || PlainPhase.RUNNING_TESTS.equals(status)) return;
        printPlainSnapshot(currentPlainPercent(), status, false);
    }

    /** Module finished: print immediately with {@code built} (do not wait for a 20% boundary). */
    void emitPlainModuleBuilt(String module) {
        if (m.done || !m.animate) return;
        m.plainProgressMode = true;
        maybePrintPlainInitializing();
        String subject = module == null ? "" : module;
        printPlainSnapshot(currentPlainPercent(), PlainPhase.BUILT, false, subject, "");
    }

    /**
     * Verbose-only sub-step detail (e.g. native classpath size). Same module/phase as the last
     * line is allowed because the detail is the point.
     */
    void emitPlainStepDetail(String detail) {
        if (m.done || !m.animate) return;
        if (!cc.jumpkick.config.SessionContext.current().config().verboseOr(false)) return;
        String extra = detail == null ? "" : PlainAscii.transform(detail);
        if (extra.isBlank()) return;
        m.plainProgressMode = true;
        maybePrintPlainInitializing();
        printPlainSnapshot(currentPlainPercent(), currentPlainStatus(), false, currentPlainSubject(), extra);
    }

    private void maybePrintPlainInitializing() {
        if (m.plainChromeStarted || m.done) return;
        m.out.println(JkWedge.plainWedge(Glyphs.PULSE_PLAIN, planName(), PlainPhase.INITIALIZING));
        m.plainChromeStarted = true;
        m.out.flush();
    }

    /** Indeterminate plain start/heartbeat (simple mode open, or plan without progress). */
    void printPlainIndeterminate(boolean forceStart) {
        synchronized (m.lock) {
            if (m.done) return;
            if (m.plainChromeStarted && !forceStart) return;
            if (m.plainChromeStarted && m.plainProgressMode) return;
            m.out.println(plainIndeterminateLine(false));
            m.plainChromeStarted = true;
            m.out.flush();
        }
    }

    /** Mandatory plain done line — progress ends at {@code 100% - done}. */
    void printPlainDone() {
        if (!m.animate) return;
        if (m.plainProgressMode) {
            if (m.plainLastDecade < 0) {
                maybePrintPlainInitializing();
                m.plainLastDecade = 0;
            }
            printPlainSnapshot(100, PlainPhase.DONE, true, planCoordOrEmpty(), "");
            return;
        }
        if (m.plainChromeStarted || !m.planMode) {
            if (!m.plainChromeStarted) {
                m.out.println(plainIndeterminateLine(false));
            }
            m.out.println(plainIndeterminateLine(true));
            m.plainChromeStarted = true;
            m.out.flush();
        }
    }

    /**
     * {@code "jk: * Format > Examining source files :: 10% - prepare"} or {@code coord :: 100% - done}.
     */
    static String plainProgressLine(String command, String message, int percent, boolean done) {
        String subject = (message == null || message.isBlank()) ? "" : message;
        if (done) {
            return JkWedge.plainWedge(
                    Glyphs.PULSE_PLAIN,
                    command == null ? "" : command,
                    formatPlainTail(subject, 100, null, PlainPhase.DONE, ""));
        }
        return JkWedge.plainWedge(
                Glyphs.PULSE_PLAIN,
                command == null ? "" : command,
                formatPlainTail(subject, percent, null, PlainPhase.PREPARE, ""));
    }

    private void printPlainSnapshot(int percent, String status, boolean doneLine) {
        printPlainSnapshot(percent, status, doneLine, currentPlainSubject(), "");
    }

    private void printPlainSnapshot(int percent, String status, boolean doneLine, String subject, String detail) {
        String line = doneLine
                ? JkWedge.plainWedge(
                        Glyphs.PULSE_PLAIN, planName(), formatPlainTail(subject, 100, null, PlainPhase.DONE, ""))
                : JkWedge.plainWedge(
                        Glyphs.PULSE_PLAIN,
                        planName(),
                        formatPlainTail(subject, percent, plainEtaClock(), status, detail));
        if (!doneLine && !shouldPrintPlain(percent, status, subject, detail)) return;
        m.plainLastSubject = subject == null ? "" : subject;
        m.plainLastStatus = status == null ? "" : status;
        m.out.println(line);
        m.plainChromeStarted = true;
        if (!doneLine && percent >= 0) {
            int decade = Math.min(80, (percent / Progress.PLAIN_STEP_PERCENT) * Progress.PLAIN_STEP_PERCENT);
            if (decade > m.plainLastDecade) m.plainLastDecade = decade;
        }
        m.out.flush();
    }

    /**
     * Print on a new module/phase, a 20% boundary, {@code start}/{@code built}/{@code prepare}, or a
     * verbose detail. Same module+phase with only an ETA/percent tick is suppressed.
     */
    private boolean shouldPrintPlain(int percent, String status, String subject, String detail) {
        if (detail != null && !detail.isBlank()) return true;
        String sub = subject == null ? "" : subject;
        String st = status == null ? "" : status;
        boolean same = sub.equals(m.plainLastSubject) && PlainPhase.sameFamily(st, m.plainLastStatus);
        if (!same) return true;
        return percent > 0 && percent < 100 && percent % Progress.PLAIN_STEP_PERCENT == 0;
    }

    private static String formatPlainTail(String subject, int percent, String eta, String status, String detail) {
        String st = status == null || status.isBlank() ? PlainPhase.PREPARE : status;
        StringBuilder tail = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            tail.append(subject).append(" :: ");
        }
        tail.append(percent).append('%');
        boolean showEta =
                eta != null && !eta.isBlank() && !PlainPhase.PREPARE.equals(st) && !PlainPhase.DONE.equals(st);
        if (showEta) {
            tail.append(" (ETA ~").append(eta).append(')');
        }
        tail.append(" - ").append(st);
        if (detail != null && !detail.isBlank()) {
            tail.append(" = ").append(detail);
        }
        return tail.toString();
    }

    private String planCoordOrEmpty() {
        return m.planCoord == null ? "" : m.planCoord;
    }

    /** {@code "jk: * Format > Examining source files - working..."} / {@code … - done.}. */
    static String plainIndeterminateLine(String command, String message, boolean done) {
        if (command == null || command.isEmpty()) {
            String msg = (message == null || message.isBlank()) ? "working" : message;
            String tail = msg + " - " + (done ? "done." : "working...");
            return JkWedge.PLAIN_LINE_PREFIX + Glyphs.PULSE_PLAIN + " " + tail;
        }
        if (done) {
            return JkWedge.plainWedge(Glyphs.PULSE_PLAIN, command, "100% - done");
        }
        String msg = (message == null || message.isBlank()) ? PlainPhase.INITIALIZING : message;
        return JkWedge.plainWedge(Glyphs.PULSE_PLAIN, command, msg);
    }

    private String plainIndeterminateLine(boolean doneLine) {
        if (m.planMode) {
            return plainIndeterminateLine(planName(), doneLine ? null : PlainPhase.INITIALIZING, doneLine);
        }
        return plainIndeterminateLine(null, m.label, doneLine);
    }

    private String currentPlainStatus() {
        JkManager.Row active = firstActiveRow();
        if (active != null) {
            if (active.plainStatusOverride != null && !active.plainStatusOverride.isEmpty()) {
                return active.plainStatusOverride;
            }
            if (active.plainRemainingTests >= 0) {
                return PlainPhase.runningTests(active.plainRemainingTests);
            }
            return PlainPhase.status(active.phase != null ? active.phase : active.step);
        }
        String sl = m.solveLabel;
        if (sl != null && !sl.isEmpty()) return PlainPhase.status(sl);
        return PlainPhase.PREPARE;
    }

    private String currentPlainSubject() {
        JkManager.Row active = firstActiveRow();
        if (active != null) {
            if (active.module != null && !active.module.isEmpty()) return active.module;
            if (active.message != null && !active.message.isEmpty()) return active.message;
            if (active.step != null && !active.step.isEmpty()) return active.step;
        }
        if (m.planCoord != null && !m.planCoord.isEmpty()) return m.planCoord;
        if (m.target != null && !m.target.isEmpty()) return m.target;
        return "";
    }

    /** Newest active row (insertion order) — the step that just started owns the status. */
    private JkManager.Row firstActiveRow() {
        JkManager.Row last = null;
        for (JkManager.Row r : m.rows.values()) {
            if (r.state == JkManager.RowState.ACTIVE) last = r;
        }
        return last;
    }

    private int currentPlainPercent() {
        long[] bd = m.displayBar(m.elapsedMillis());
        if (bd[1] <= 0) return Math.max(0, m.plainLastDecade);
        return new Progress(bd[0], bd[1]).percent();
    }

    private String plainEtaClock() {
        long remMs = plainRemainingMs();
        if (remMs < 0) return null;
        return JkManagerColor.fmtClock(remMs);
    }

    private long plainRemainingMs() {
        long elapsed = m.elapsedMillis();
        if (m.residualRemainingMs >= 0 && (m.remainingWorkMs >= 0 || m.residualRemainingMs > 0)) {
            return Math.max(0L, m.residualRemainingMs - Math.max(0L, elapsed - m.residualSetAtElapsedMs));
        }
        if (m.remainingWorkMs >= 0) {
            return Math.max(0L, m.remainingWorkMs - Math.max(0L, elapsed - m.remainingSetAtElapsedMs));
        }
        return -1L;
    }

    /**
     * Leading blank once per command. Shared with prep spinners via
     * {@link CommandWedge#envelopeStart(PrintStream)} so lock/analyze wedges and the live region
     * do not double-space.
     */
    void ensureLeadingBlank() {
        if (m.leadingBlankPrinted) return;
        m.leadingBlankPrinted = true;
        CommandWedge.envelopeStart(m.out);
    }

    /** Repaint the single simple-mode spinner line in place (must hold the manager lock). */
    void paintSimple() {
        m.out.print('\r');
        m.out.print(Theme.colorize(m.PULSE, m.openPulseColors[Math.floorMod(m.frame, m.openPulseColors.length)]));
        m.out.print(' ');
        m.out.print(m.label);
        m.out.print(m.ELLIPSIS);
        m.out.print(Ansi.ERASE_LINE_TO_END);
        m.out.print(Ansi.taskbarIndeterminate());
    }

    /**
     * Route process/step output through the sliding {@link OutputWindow}.
     *
     * <p>Plan mode always buffers. When the peek pane is <em>open</em>, process lines are committed
     * to terminal scrollback above the live region (rule + wedge + tree) and only that small live
     * region is rewritten — not the whole screen, and not on every animator tick. When hidden,
     * lines stay in the ring until Ctrl-O reveals them. Simple mode keeps the old permanent-above
     * spinner behavior.
     */
    /**
     * Compiler / test / native-image stdout. Always buffered for the peek ring. Printed in plain
     * mode only when {@code -v}/{@code --verbose} is set.
     */
    public void writeProcessOutput(String text) {
        if (text == null) return;
        boolean verbose = cc.jumpkick.config.SessionContext.current().config().verboseOr(false);
        if (!Theme.active().isAnsi() && !verbose) {
            synchronized (m.lock) {
                if (m.planMode && !m.done) m.outputWindow.append(text);
            }
            return;
        }
        writeAbove(text);
    }

    /**
     * Plain {@code --no-ansi} TTY: there is no Ctrl-O listener and no settle dump, so a
     * tool/worker crash would leave its only evidence (the buffered stdout ring) invisible.
     * Print the uncommitted ring lines sequentially — they land directly above the failure
     * wedge the settle is about to emit (JK-2163).
     */
    void dumpPlainProcessOutput() {
        synchronized (m.lock) {
            var pending = m.outputWindow.uncommittedForDisplay(OutputWindow.MAX_LINES);
            for (String line : pending) m.out.println(line);
            m.outputWindow.markAllCommitted();
            if (!pending.isEmpty()) m.out.flush();
        }
    }

    public void writeAbove(String text) {
        if (text != null && text.indexOf('\n') >= 0) {
            // DiagnosticReport and other multi-line blobs must be one scrollback row each.
            // A single append + truncateVisible would squash rails onto one terminal line.
            for (String part : text.split("\n", -1)) {
                if (part.endsWith("\r")) part = part.substring(0, part.length() - 1);
                writeAbove(part);
            }
            return;
        }
        synchronized (m.lock) {
            if (m.done) {
                m.out.println(text);
                m.out.flush();
                return;
            }
            if (m.planMode) {
                if (text == null) return;
                // append() reports blank-strips; a size compare would misread ring-full
                // eviction (size unchanged on every accepted append) as a strip.
                boolean accepted = m.outputWindow.append(text);
                if (!m.animate || !Theme.active().isAnsi()) {
                    // No live region to lift: print diagnostics sequentially. Tool stdout uses
                    // writeProcessOutput (silent in plain mode unless --verbose).
                    m.out.println(text);
                    m.out.flush();
                    return;
                }
                if (!accepted) return; // blank-stripped: nothing new for the live region
                if (m.outputWindow.visible()) {
                    // Lift live region → emit one line into scrollback → repaint rule+wedge only.
                    liftEmitRepaintLive(text);
                }
                return;
            }
            if (!m.animate) {
                m.out.println(text);
                m.out.flush();
                return;
            }
            m.out.print(Ansi.CLEAR_LINE);
            m.out.print(text);
            m.out.print('\n');
            paintSimple();
            m.out.flush();
        }
    }

    /**
     * Repaint the multi-line <em>live</em> plan region (separator + wedge + tree). Separator is the
     * braille rule when peek is on, or a blank line when peek is off after process lines were
     * committed. Process output in scrollback above is never redrawn here.
     *
     * <p>Cursor invariant: between paints the cursor is parked at the start of the line immediately
     * below the live region. Line-diff only rewrites changed rows (spinner header most frames).
     */
    void paintBuildPlan() {
        syncTerminalSize();
        long elapsed = m.elapsedMillis();
        List<String> lines = m.renderBuildPlanLines(m.width, elapsed);
        int colBudget = JkManagerColor.rowColumnBudget(m.width);
        boolean force = forceFullRepaint;
        forceFullRepaint = false;
        int prev = m.lastLines.size();
        int maxUp = OutputWindow.maxRegionLines(m.height);
        int up = Math.min(prev, maxUp);
        if (up > 0) m.out.print(Ansi.cursorUp(up));
        for (int i = 0; i < lines.size(); i++) {
            boolean changed = force || i >= prev || !lines.get(i).equals(m.lastLines.get(i));
            if (changed) {
                m.out.print('\r');
                m.out.print(JkManagerColor.truncateVisible(lines.get(i), colBudget));
                m.out.print(Ansi.ERASE_LINE_TO_END);
            }
            m.out.print('\n');
        }
        if (prev > lines.size()) m.out.print(Ansi.ERASE_DISPLAY_TO_END);
        long[] bd = m.displayBar(elapsed);
        m.out.print(Ansi.taskbarProgress(ProgressBar.percent(bd[0], bd[1])));
        if (m.animate && !Theme.active().isAnsi() && bd[1] > 0) {
            synchronized (m.lock) {
                m.emitPlainProgressDecades(bd[0], bd[1]);
            }
        }
        m.lastLines = lines;
        m.linesDrawn = lines.size();
        paintedCols = m.width;
    }

    /**
     * Peek open: lift the live region, print buffered process lines into scrollback, paint rule +
     * wedge. Must hold {@code m.lock}.
     */
    void openPeekPaint() {
        if (!m.animate || !Theme.active().isAnsi()) return;
        syncTerminalSize();
        List<String> chrome = renderChromeLines(m.width, m.elapsedMillis());
        int budget = OutputWindow.displayBudget(m.height, chrome.size());
        // Uncommitted only: lines from an earlier open (dump or live appends) are already
        // permanent scrollback right above — re-dumping them duplicates (JK-2092).
        List<String> pane = m.outputWindow.uncommittedForDisplay(budget);
        int colBudget = JkManagerColor.rowColumnBudget(m.width);
        int up = Math.min(m.lastLines.size(), OutputWindow.maxRegionLines(m.height));
        if (up > 0) {
            m.out.print(Ansi.cursorUp(up));
            m.out.print('\r');
            m.out.print(Ansi.ERASE_DISPLAY_TO_END);
        }
        for (String line : pane) {
            m.out.print('\r');
            m.out.print(JkManagerColor.truncateVisible(line, colBudget));
            m.out.print(Ansi.ERASE_LINE_TO_END);
            m.out.print('\n');
        }
        m.outputWindow.noteCommitted(pane.size());
        m.outputWindow.markAllCommitted();
        writeLiveRegion(liveRegionLines(chrome, m.width));
        m.out.flush();
    }

    /**
     * Peek close: leave committed process lines in scrollback; replace the dotted rule with a blank
     * separator (still between process output and the wedge) and repaint chrome. Must hold
     * {@code m.lock}. Caller has already {@link OutputWindow#hide()}'d.
     */
    void closePeekPaint() {
        if (!m.animate || !Theme.active().isAnsi()) return;
        syncTerminalSize();
        int up = Math.min(m.lastLines.size(), OutputWindow.maxRegionLines(m.height));
        if (up > 0) {
            m.out.print(Ansi.cursorUp(up));
            m.out.print('\r');
            m.out.print(Ansi.ERASE_DISPLAY_TO_END);
        }
        // liveRegionLines paints "" when peek is off but process lines were committed.
        writeLiveRegion(liveRegionLines(renderChromeLines(m.width, m.elapsedMillis()), m.width));
        m.out.flush();
    }

    /**
     * Append one process line below existing output (above the live region), then repaint rule +
     * wedge. Does not redraw prior process lines. Must hold {@code m.lock}.
     */
    private void liftEmitRepaintLive(String text) {
        syncTerminalSize();
        int colBudget = JkManagerColor.rowColumnBudget(m.width);
        String painted = JkManagerColor.truncateVisible(text, colBudget);
        int up = Math.min(m.lastLines.size(), OutputWindow.maxRegionLines(m.height));
        if (up > 0) {
            m.out.print(Ansi.cursorUp(up));
            m.out.print('\r');
            m.out.print(Ansi.ERASE_DISPLAY_TO_END);
        }
        m.out.print('\r');
        m.out.print(painted);
        m.out.print(Ansi.ERASE_LINE_TO_END);
        m.out.print('\n');
        m.outputWindow.noteCommitted(1);
        m.outputWindow.markAllCommitted();
        List<String> chrome = renderChromeLines(m.width, m.elapsedMillis());
        writeLiveRegion(liveRegionLines(chrome, m.width));
        m.out.flush();
    }

    /** Write live-region lines from the current cursor and update lastLines / linesDrawn. */
    private void writeLiveRegion(List<String> live) {
        int colBudget = JkManagerColor.rowColumnBudget(m.width);
        for (String line : live) {
            m.out.print('\r');
            m.out.print(JkManagerColor.truncateVisible(line, colBudget));
            m.out.print(Ansi.ERASE_LINE_TO_END);
            m.out.print('\n');
        }
        m.lastLines = live;
        m.linesDrawn = live.size();
        paintedCols = m.width;
        long[] bd = m.displayBar(m.elapsedMillis());
        m.out.print(Ansi.taskbarProgress(ProgressBar.percent(bd[0], bd[1])));
    }

    /**
     * Live region only: separator + wedge/tree chrome (never includes process lines).
     *
     * <p>When process output has been committed above the region, keep exactly one separator row
     * between that scrollback and the wedge: the braille rule while peek is on, or a blank line
     * when peek is off. Never omit the separator after process lines were shown.
     */
    private List<String> liveRegionLines(List<String> chrome, int cols) {
        List<String> live = new ArrayList<>();
        if (m.outputWindow.visible()) {
            live.add(OutputWindow.ruleLine(cols));
        } else if (m.outputWindow.committedScrollbackLines() > 0) {
            live.add(""); // blank stand-in for the rule
        }
        live.addAll(chrome);
        int maxRegion = OutputWindow.maxRegionLines(m.height);
        if (live.size() > maxRegion) {
            // Overflow drops TREE rows, never the separator or the header: a plain tail-slice
            // deleted the rule/blank first (violating the invariant above) and, one more over,
            // the spinner header too (JK-2106). Keep separator rows + chrome head, then fill
            // the rest with the newest chrome tail rows.
            int separatorRows = live.size() - chrome.size();
            List<String> trimmed = new ArrayList<>(maxRegion);
            for (int i = 0; i < separatorRows && trimmed.size() < maxRegion; i++) trimmed.add(live.get(i));
            if (!chrome.isEmpty() && trimmed.size() < maxRegion) trimmed.add(chrome.getFirst());
            int room = maxRegion - trimmed.size();
            if (room > 0 && chrome.size() > 1) {
                trimmed.addAll(chrome.subList(Math.max(1, chrome.size() - room), chrome.size()));
            }
            live = trimmed;
        }
        return live;
    }

    /**
     * Pull columns/rows from the process-wide cache (one ioctl only if SIGWINCH cleared it). On a
     * real size change, mark a full rewrite so every row re-truncates under the new budget. On a
     * column change with a live region, wipe first — after a shrink the terminal may have reflowed
     * the old paint onto more physical rows than {@code lastLines.size()}.
     */
    private void syncTerminalSize() {
        int[] size = TerminalSize.size();
        int cols = size[1] > 0 ? size[1] : m.width;
        int rows = size[0] > 0 ? size[0] : m.height;
        if (cols == m.width && rows == m.height) return;
        if (cols != m.width && !m.lastLines.isEmpty()) {
            wipeReflowedRegion(paintedCols > 0 ? paintedCols : m.width, cols);
        }
        m.width = cols;
        m.height = rows;
        // Content often unchanged after a maximize; force rewrite so truncateVisible uses the
        // new budget (and so a shrink re-clips + EL-clears the prior tail).
        forceFullRepaint = true;
    }

    /**
     * Move to the top of the (possibly reflowed) live region and erase it so the next paint does not
     * stack under orphans. Cursor is left at the start of the former region — paint treats this as a
     * first frame ({@code lastLines} cleared).
     */
    private void wipeReflowedRegion(int fromCols, int toCols) {
        // Clipping terminals (xterm, linux console, screen, …) keep exactly one physical row per
        // logical line: climbing the reflow estimate there overshoots into completed output above
        // the region and ERASE_DISPLAY_TO_END destroys it. Only terminals known to
        // rewrap get the reflow-height climb.
        int up = m.lastLines.size();
        if (TerminalReflow.reflows()) {
            up = Math.max(physicalRowsAfterReflow(m.lastLines, fromCols, toCols), up);
        }
        if (up > 0) m.out.print(Ansi.cursorUp(up));
        m.out.print('\r');
        m.out.print(Ansi.ERASE_DISPLAY_TO_END);
        m.lastLines = List.of();
        m.linesDrawn = 0;
    }

    /**
     * Physical rows occupied by {@code lines} after the terminal reflows from {@code fromCols} to
     * {@code toCols}. Each line was painted at most {@link JkManagerColor#rowColumnBudget(int)} of
     * {@code fromCols} wide (one row then); after a shrink, reflow wraps that text to
     * {@code ceil(painted / toCols)} rows.
     */
    static int physicalRowsAfterReflow(List<String> lines, int fromCols, int toCols) {
        if (lines == null || lines.isEmpty()) return 0;
        int width = Math.max(1, toCols);
        int fromBudget = JkManagerColor.rowColumnBudget(Math.max(1, fromCols));
        int rows = 0;
        for (String line : lines) {
            int painted = Math.min(RenderContext.visibleWidth(line), fromBudget);
            if (painted <= 0) {
                rows += 1; // blank logical line still occupies a row
            } else {
                rows += (painted + width - 1) / width;
            }
        }
        return rows;
    }

    /**
     * Build the plan region's lines (header-with-bar, compact module/phase tree, completed
     * tail). Pure — no cursor control. Package-private for tests.
     *
     * <p>Tree (newest at top): only <em>running</em> and <em>failed</em> work — successful steps drop
     * stdout. Each row is {@code ├─ ● group:name › Phase › detail} with a blue pulse spinner while
     * running (no background pills). The trailing detail is the latest step {@link #stepMessage}
     * (test class, package sub-task, fetch artifact, …). Failed rows use a red cross and keep a
     * one-line brief under the branch. No blank spacer rails between rows — vertically compact.
     */
    /**
     * Live region only: separator (rule or blank) + wedge/tree chrome. Process lines are not
     * included — once shown they are terminal scrollback above this region.
     */
    public List<String> renderBuildPlanLines(int cols, long elapsedMillis) {
        return liveRegionLines(renderChromeLines(cols, elapsedMillis), cols);
    }

    /** Wedge header + tree + completions (no rule, no process lines). */
    List<String> renderChromeLines(int cols, long elapsedMillis) {
        AttributedStyle dim = Theme.active().darkGray();
        List<String> chrome = new ArrayList<>();
        RenderContext frameCtx = RenderContext.current().withWidth(cols);
        boolean hasScopeHint = !m.scopeHintVerb.isEmpty() && !m.scopeHintNames.isEmpty();
        if (hasScopeHint) {
            chrome.add(ModuleScopeHint.line(m.scopeHintVerb, m.scopeHintNames, frameCtx));
        }
        chrome.add(planHeader(frameCtx, elapsedMillis));

        int rowsAfterHeader = Math.max(1, m.height - 2 - (hasScopeHint ? 1 : 0));
        // Shrink the work tree so ✓ [N of M] still fits under the wedge (live region only).
        int completionSlots = 0;
        if (m.completedCount > 0 && rowsAfterHeader > 1) {
            completionSlots = Math.min(JkManager.MAX_COMPLETIONS + 1, Math.max(1, rowsAfterHeader / 2));
        }
        int budget = Math.max(1, rowsAfterHeader - completionSlots);
        List<JkManager.TreeEntry> visible = collectVisibleTree();
        Tree work = Tree.untitled().gap(Tree.Gap.NONE);
        int shown = 0;
        for (int i = 0; i < visible.size() && budget > 0; i++) {
            JkManager.TreeEntry entry = visible.get(i);
            String rowLabel = entry.line == null ? "" : entry.line.stripLeading();
            Tree.Node node = Tree.node(RichText.ansi(rowLabel));
            budget--;
            shown++;
            if (entry.briefError != null && !entry.briefError.isEmpty() && budget > 0) {
                node.body(RichText.ansi(
                                Theme.colorize(entry.briefError, Theme.active().error())))
                        .bodyFit(Tree.BodyFit.INDENT);
                budget--;
            }
            work.child(node);
            if (shown >= JkManager.MAX_ROWS) break;
        }
        chrome.addAll(work.render(frameCtx));

        if (m.completedCount > 0) {
            int room = Math.max(0, rowsAfterHeader - (chrome.size() - 1));
            if (room > 0) {
                boolean overflow = m.completedCount > Math.min(JkManager.MAX_COMPLETIONS, room);
                int cap = Math.max(0, Math.min(JkManager.MAX_COMPLETIONS, overflow ? room - 1 : room));
                int have = m.recentCompletions.size();
                int compShown = Math.min(have, cap);
                for (int i = 0; i < compShown; i++) {
                    chrome.add("    " + m.recentCompletions.get(have - 1 - i));
                }
                int more = m.completedCount - compShown;
                if (more > 0) {
                    chrome.add(Theme.colorize("      … plus " + more + " more …", dim.italic()));
                }
            }
        }
        return chrome;
    }

    /** Running/failed tree entries, newest first. Module rows when available; else preflight phases. */
    private List<JkManager.TreeEntry> collectVisibleTree() {
        List<JkManager.TreeEntry> entries = new ArrayList<>();
        List<JkManager.Row> active = new ArrayList<>();
        List<JkManager.Row> failed = new ArrayList<>();
        for (JkManager.Row r : m.rows.values()) {
            if (r.state == JkManager.RowState.ACTIVE) active.add(r);
            else if (r.state == JkManager.RowState.FAILED) failed.add(r);
        }
        if (!active.isEmpty() || !failed.isEmpty()) {
            for (int i = active.size() - 1; i >= 0; i--) entries.add(treeEntryForRow(active.get(i)));
            failed.sort((a, b) -> Long.compare(b.seq, a.seq));
            for (JkManager.Row r : failed) entries.add(treeEntryForRow(r));
            return entries;
        }
        for (int i = m.phaseOrder.size() - 1; i >= 0; i--) {
            JkManager.PhaseNode n = m.phases.get(m.phaseOrder.get(i));
            if (n != null && (n.state == JkManager.PhaseState.RUNNING || n.state == JkManager.PhaseState.FAILED)) {
                entries.add(treeEntryForPhase(n));
            }
        }
        return entries;
    }

    private JkManager.TreeEntry treeEntryForRow(JkManager.Row r) {
        boolean failed = r.state == JkManager.RowState.FAILED;
        String brief = failed ? r.briefError : "";
        if ((brief == null || brief.isEmpty()) && failed) {
            JkManager.PhaseNode n = m.phases.get(r.phase);
            if (n != null) brief = n.briefError;
        }
        String detail = failed ? "" : JkManagerColor.detailForDisplay(r.module, r.message);
        return new JkManager.TreeEntry(
                renderWorkRow(r.module, JkManagerColor.phaseLabel(r.phase), failed, detail),
                brief == null ? "" : brief);
    }

    private JkManager.TreeEntry treeEntryForPhase(JkManager.PhaseNode n) {
        boolean failed = n.state == JkManager.PhaseState.FAILED;
        String phaseLabel = n.label == null || n.label.isEmpty() ? JkManagerColor.phaseLabel(n.key) : n.label;
        String detail = failed ? "" : (n.detail == null ? "" : n.detail);
        return new JkManager.TreeEntry(renderWorkRow("", phaseLabel, failed, detail), failed ? n.briefError : "");
    }

    /**
     * Step label for the tree detail segment. Strips a leading {@code module:: } prefix when the
     * engine label already embeds the coordinate (test progress labels) so the row does not read
     * {@code g:a › Test › g:a:: FooTest}.
     */
    static String detailForDisplay(String module, String message) {
        return JkManagerColor.detailForDisplay(module, message);
    }

    static String renderBriefErrorLine(boolean last, String brief) {
        return JkManagerColor.renderBriefErrorLine(last, brief);
    }

    /** Dim segment separator between module, phase, and detail on a work row. */
    private static final String WORK_ROW_SEP = "›";

    private String renderWorkRow(String module, String displayPhase, boolean failed, String detail) {
        Theme t = Theme.active();
        String icon;
        AttributedStyle phaseStyle;
        if (failed) {
            icon = Theme.colorize(Glyphs.CROSS, t.error());
            phaseStyle = t.error();
        } else {
            // Filling circle (○→◎→◉→◎) in constant blue — not the CommandWedge color-pulse ●.
            icon = Theme.colorize(Spinner.fillGlyph(m.frame), t.blue());
            // Running phase: bold blue (matches web running chips; spinner stays plain blue).
            phaseStyle = t.blue().bold();
        }
        String phase = displayPhase == null || displayPhase.isEmpty() ? "?" : displayPhase;
        String sep = Theme.colorize(WORK_ROW_SEP, t.darkGray());
        StringBuilder sb = new StringBuilder();
        sb.append(' ').append(icon).append(' ');
        if (module != null && !module.isEmpty()) {
            sb.append(JkManagerColor.coloredModule(module))
                    .append(' ')
                    .append(sep)
                    .append(' ');
        }
        sb.append(Theme.colorize(phase, phaseStyle));
        if (detail != null && !detail.isBlank()) {
            sb.append(' ').append(sep).append(' ').append(colorDetail(phase, detail, t));
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
     * <li>integers / counts / sizes → yellow ({@link Theme#warning})
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
        return JkManagerColor.colorDetail(phase, detail, t);
    }

    static String colorNativeClasspathSizeDetail(String detail, Theme t) {
        return JkManagerColor.colorNativeClasspathSizeDetail(detail, t);
    }

    static String colorProseDetail(String text, Theme t) {
        return JkManagerColor.colorProseDetail(text, t);
    }

    static boolean looksLikeSizeUnit(String unit) {
        return JkManagerColor.looksLikeSizeUnit(unit);
    }

    static boolean isFetchOrResolveVerb(String word) {
        return JkManagerColor.isFetchOrResolveVerb(word);
    }

    static boolean looksLikeLibraryShortName(String tok) {
        return JkManagerColor.looksLikeLibraryShortName(tok);
    }

    static boolean looksLikeCoord(String tok) {
        return JkManagerColor.looksLikeCoord(tok);
    }

    static String colorCoord(String tok) {
        return JkManagerColor.colorCoord(tok);
    }

    static boolean looksLikePathOrArtifact(String tok) {
        return JkManagerColor.looksLikePathOrArtifact(tok);
    }

    static boolean looksLikeJavaMember(String s) {
        return JkManagerColor.looksLikeJavaMember(s);
    }

    RenderContext headerContext() {
        return RenderContext.current().withCaps(m.nerdFont).withFrame(m.frame);
    }

    /**
     * BuildPlan header: pulse circle + name on the chip, powerline (or plain) cap, bar, clock.
     * Painted via {@link JkWedge} so live and settled chrome share one renderer.
     */
    private String planHeader(RenderContext frameCtx, long elapsedMillis) {
        long[] bd = m.displayBar(elapsedMillis);
        long barNum = bd[0];
        long barDen = bd[1];
        // Sample worker-written state under the lock — the animator thread otherwise read
        // m.denominator/m.solveLabel on plain JMM visibility.
        long den;
        String sl;
        synchronized (m.lock) {
            den = m.denominator;
            sl = m.solveLabel;
        }
        boolean hasBar = barDen > 0 || den > 0;
        boolean phase1 = !hasBar && !sl.isEmpty();
        long elapsedSec = Math.max(0L, elapsedMillis) / 1000L;
        // Dual clock when we have ever received a remaining-work seed (including residual 0 done).
        // Prefer residual re-anchor (eases into R(t), ends on time); else frozen R0 − elapsed.
        // Deadline = setAt + R so m.target remainingSec and elapsedSec share whole-second boundaries.
        long remainingSec;
        boolean seeded;
        long overrunSec = 0;
        synchronized (m.lock) {
            long anchorRem;
            long anchorAt;
            if (m.residualRemainingMs >= 0 && (m.remainingWorkMs >= 0 || m.residualRemainingMs > 0)) {
                // Residual known (including 0 after R0 was seeded) — countdown tracks R(t).
                seeded = true;
                anchorRem = m.residualRemainingMs;
                anchorAt = m.residualSetAtElapsedMs;
            } else if (m.remainingWorkMs >= 0) {
                seeded = true;
                anchorRem = m.remainingWorkMs;
                anchorAt = m.remainingSetAtElapsedMs;
            } else {
                seeded = false;
                anchorRem = 0;
                anchorAt = 0;
            }
            if (seeded) {
                long deadlineMs = anchorAt + anchorRem;
                long deadlineSec = deadlineMs / 1000L;
                long targetSec = Math.max(0L, deadlineSec - elapsedSec);
                // Jitter buffer: sample latest m.target at most once per whole-second elapsed tick.
                // Same-second residual re-anchors update the private m.target only; the painted face
                // holds until elapsedSec advances (or first paint / seed / snap-to-zero). One
                // asymmetry is deliberate the other way: a re-anchor that RAISES the m.target in the
                // same second a zero was committed repaints immediately — holding the 0s until the
                // next second manufactured a 0s → Ns bounce.
                if (m.countdownDisplayElapsedSec < 0
                        || elapsedSec != m.countdownDisplayElapsedSec
                        || targetSec == 0
                        || m.countdownDisplayRemainingSec <= 0) {
                    m.countdownDisplayRemainingSec = targetSec;
                    m.countdownDisplayElapsedSec = elapsedSec;
                }
                remainingSec = m.countdownDisplayRemainingSec;
                if (remainingSec <= 0) {
                    // How far past the residual/R0 deadline, on the same whole-second counter.
                    overrunSec = Math.max(0L, elapsedSec - deadlineSec);
                }
            } else {
                remainingSec = 0;
                m.countdownDisplayElapsedSec = -1;
            }
        }
        RichText clock = clockFace(seeded, remainingSec, overrunSec, elapsedSec);
        RenderContext ctx = frameCtx.withCaps(m.nerdFont).withFrame(m.frame);
        if (phase1) {
            RichText msg = RichText.of(
                    RichText.ansi(Theme.colorize(sl, Theme.active().brightWhite())), RichText.plain(" "), clock);
            return new JkWedge(Icon.spinner(), m.name, msg)
                    .variant(JkWedge.Variant.WORK)
                    .renderLine(ctx);
        }
        return new JkWedge(Icon.spinner(), m.name, RichText.empty())
                .variant(JkWedge.Variant.WORK)
                .progress(new Progress(hasBar ? barNum : 0, hasBar ? Math.max(1, barDen) : 0).suffix(clock))
                .renderLine(ctx);
    }

    private RichText clockFace(boolean seeded, long remainingSec, long overrunSec, long elapsedSec) {
        String elapsed = JkManagerColor.fmtClockSeconds(elapsedSec);
        if (!seeded) {
            return RichText.parse("[dark-gray]·[/] [mid-gray]" + elapsed + "[/]");
        }
        // Countdown stays mid-gray: ~remaining, then 0s, then +overrun. Elapsed stays dim, no +.
        String rem;
        if (remainingSec > 0) {
            rem = "[mid-gray]~" + JkManagerColor.fmtClockSeconds(remainingSec) + "[/]";
        } else if (overrunSec > 0) {
            rem = "[mid-gray]+" + JkManagerColor.fmtClockSeconds(overrunSec) + "[/]";
        } else {
            rem = "[mid-gray]0s[/]";
        }
        return RichText.parse(
                "[dark-gray]·[/] [dark-gray italic]ETA [/]" + rem + " [dark-gray]·[/] [dark-gray]" + elapsed + "[/]");
    }

    /**
     * Countdown/elapsed duration from milliseconds: {@code "42s"}, {@code "1m 02s"},
     * {@code "1h 05m 09s"} (units past the lead zero-padded).
     */
}
