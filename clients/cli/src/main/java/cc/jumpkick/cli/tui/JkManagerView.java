// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.Osc;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.DurationText;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Size;
import cc.jumpkick.terminal.Style;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/** Finish / settle / plan-paint collaborator for {@link JkManager} — the ANSI half of the mode axis. */
@NullMarked
final class JkManagerView {

    private final JkManager m;

    /**
     * When true, the next {@link #paintBuildPlan()} rewrites every row even where the text is
     * unchanged: a terminal resize changed the truncation budget, or a lift-and-rewrite path (peek
     * toggle, force-show) repainted from a different geometry than the diff would assume.
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

    /**
     * Settle with {@code ✔ <plan> Successful: <message>} (the head in green), first printing
     * {@code above} — buffered subprocess output (compiler warnings, &c.) — as scrollback
     * above the result line, so the {@code ✔
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
        m.settle(JkWedge.ok(m.planName(), tail).renderLine(headerContext()), above);
    }

    /**
     * Settle with the play chip: {@code ▶ Run Executing `java …`} — for commands that hand off to a
     * subprocess after the plan settles (e.g. {@code jk run}). {@code planName} is the
     * command label (typically {@code Run}); {@code tail} is the pre-styled message.
     *
     * <p>{@code jk run} prints its own single separator before {@code inheritIO} (no settle
     * trailing blank — settles never add one; {@link cc.jumpkick.cli.api.CliOutput#closeEnvelope} does
     * after the command returns).
     */
    public void finishBuildPlanExec(String tail, List<String> above) {
        m.settle(JkWedge.work(m.planName(), tail).renderLine(headerContext()), above);
    }

    /** Settle the build plan with the red chip: {@code ‼ Build ▶ Failure <tail>}. */
    public void finishBuildPlanFailure(String tail, List<String> above) {
        m.settle(JkWedge.failedTo(m.planName(), tail).renderLine(headerContext()), above);
    }

    /**
     * Settle as a remote engine cancel ({@code jk cancel} / web): gray {@code ⊛ Build} chip, then
     * {@code job was cancelled took …} — no "by user".
     */
    public void finishBuildPlanCancelled(List<String> above) {
        String took = ConsoleSpec.took(Duration.ofMillis(m.elapsedMillis()));
        m.settle(JkWedge.cancelled(m.planName(), false, took).renderLine(headerContext()), above);
    }

    /**
     * Settle the build plan with the red chip, but a fully caller-composed sentence instead of the
     * "Failed to &lt;plan&gt;" derivation {@link #finishBuildPlanFailure} applies — see {@link
     * JkWedge#failedTo}.
     */
    public void finishBuildPlanFailureCustom(String sentence, List<String> above) {
        m.settle(JkWedge.fail(m.planName(), RichText.ansi(sentence)).renderLine(headerContext()), above);
    }

    /** Settle with a red cross and a failure message, with buffered output printed above it. */
    public void finishFailure(String message, List<String> above) {
        m.settle(Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " " + message, above);
    }

    /**
     * Clear the live region without printing any result line — used when the plan's outcome is
     * communicated externally (e.g. via a post-plan chipLine printed by the caller). Same cleanup
     * as {@link JkManager#settle} but outputs nothing.
     */
    public void dismiss() {
        m.pane.restoreStreams();
        m.stopAnimator();
        synchronized (m.lock) {
            if (m.done) return;
            m.done = true;
            LiveRegion.clearActive(m);
            m.windowTitle.clear();
            if (m.animate && Theme.active().isAnsi()) {
                if (m.planMode) m.wipeRegion();
                else m.freezeSpinnerLine();
                m.out.print(Osc.taskbarClear());
                m.out.print(Ansi.SHOW_CURSOR);
                m.out.flush();
            } else if (m.animate && !Theme.active().isAnsi()) {
                // Plain: end multi-line chrome without a settle wedge (caller owns outcome).
                m.plain.printDone();
            } else {
                m.out.flush();
            }
        }
    }

    /**
     * Print the settled result line. One blank before chrome starts (envelope). After committed
     * process output, one blank between that scrollback and the settle chip. No blank after the
     * settle line (would look like an extra row before the shell prompt). Callers that hand off to
     * a subprocess ({@code jk run}) add their own separator when needed.
     */
    void settle(String line, List<String> above) {
        m.pane.restoreStreams(); // flush any captured output above the region first
        m.stopAnimator();
        synchronized (m.lock) {
            if (m.done) return;
            m.done = true;
            LiveRegion.clearActive(m);
            m.windowTitle.clear();
            // Process lines already in scrollback; wipe removes rule/blank + live chrome.
            int processAbove = m.planMode ? m.pane.window().committedScrollbackLines() : 0;
            if (m.animate && Theme.active().isAnsi()) {
                if (m.planMode) m.pane.flushVisibleToScrollback();
                // Simple mode keeps the settled spinner line and prints the result below it; plan
                // mode replaces the whole region (cursor lands on the first wiped row).
                if (m.planMode) m.wipeRegion();
                else m.freezeSpinnerLine();
                m.out.print(Osc.taskbarClear());
                m.out.print(Ansi.SHOW_CURSOR);
            } else if (m.animate && !Theme.active().isAnsi()) {
                // Plain multi-line: mandatory done line before the settle wedge.
                m.plain.printDone();
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
            ensureLeadingBlank(); // quiet / late m.settle still gets the leading blank
            m.out.println(line);
            m.out.flush();
            m.pane.window().resetCommitted();
        }
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
        m.out.print(Glyphs.ELLIPSIS);
        m.out.print(Ansi.ERASE_LINE_TO_END);
        m.out.print(Osc.taskbarIndeterminate());
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
        boolean verbose = SessionContext.current().config().verboseOr(false);
        if (!Theme.active().isAnsi() && !verbose) {
            synchronized (m.lock) {
                if (m.planMode && !m.done) m.pane.window().append(text);
            }
            return;
        }
        writeAbove(text);
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
                if (!m.animate || !Theme.active().isAnsi()) {
                    // No live region to lift: print diagnostics sequentially, and keep them out of
                    // the ring. The ring is the plain-mode failure dump's source (tool stdout that
                    // was never shown), and a line that was already printed rode along in it, so a
                    // later module's failure re-printed every earlier module's completion line.
                    m.out.println(text);
                    m.out.flush();
                    return;
                }
                // append() reports blank-strips; a size compare would misread ring-full
                // eviction (size unchanged on every accepted append) as a strip.
                boolean accepted = m.pane.window().append(text);
                if (!accepted) return; // blank-stripped: nothing new for the live region
                if (m.pane.window().visible()) {
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
     * below the live region.
     *
     * <p>Each frame climbs to the region top and rewrites only the rows whose text changed since
     * the last paint (most frames: the spinner header alone); an unchanged row is stepped over
     * with a bare newline. Rows the new region no longer covers — a shrink's stale tail, or
     * park-row lines a child wrote at the park — are erased below it. Growth at the bottom of
     * the viewport must pre-scroll first: otherwise the extra trailing {@code \n}s orphan the
     * header into scrollback while {@code lastLines} still counts it, and the next frame stacks
     * a second {@code ● Build}.
     *
     * <p>The whole frame is assembled and written once: a terminal that renders between writes
     * would otherwise show the half-painted region.
     */
    void paintBuildPlan() {
        syncSize();
        long elapsed = m.elapsedMillis();
        List<String> lines = m.renderBuildPlanLines(m.width, elapsed);
        // The diff assumes one physical row per painted line. A wrapped row (clipping failed) or
        // a resize (new truncation budget) misaligns lastLines against the screen: rewrite all.
        boolean force = forceFullRepaint || m.linesDrawn != m.lastLines.size();
        forceFullRepaint = false;
        int painted = Math.max(m.linesDrawn, m.lastLines.size());
        int drift = m.parkDrift.getAndSet(0);
        int next = lines.size();
        int maxUp = OutputWindow.maxRegionLines(m.height);
        int grow = next - painted;
        StringBuilder f = new StringBuilder(256);
        if (grow > 0 && painted > 0) {
            // Room first, then climb the new height plus any unlocked park-row newlines.
            for (int i = 0; i < grow; i++) f.append("\r\n");
            f.append(Ansi.cursorUp(Math.min(next + drift, maxUp)));
        } else if (painted + drift > 0) {
            f.append(Ansi.cursorUp(Math.min(painted + drift, maxUp)));
        }
        int prev = m.lastLines.size();
        int rows = 0;
        for (int i = 0; i < next; i++) {
            String line = lines.get(i);
            if (force || i >= prev || !line.equals(m.lastLines.get(i))) {
                rows += emitLine(f, line);
            } else {
                f.append('\n');
                rows++;
            }
        }
        // Rows left under the new region: a shrink's stale tail, or the park-row drift lines.
        if (painted + drift + Math.max(grow, 0) > next) {
            f.append('\r').append(Ansi.ERASE_DISPLAY_TO_END);
        }
        m.lastLines = List.copyOf(lines);
        m.linesDrawn = rows;
        paintedCols = m.width;
        appendTaskbar(f, elapsed);
        m.out.print(f.toString());
    }

    /**
     * Lift the cursor to the top of the live region and erase it. Between paints the cursor is
     * parked on the line immediately below the region.
     */
    private void liftRegion(StringBuilder f) {
        int up = Math.min(m.climbRows(), OutputWindow.maxRegionLines(m.height));
        if (up == 0) return;
        f.append(Ansi.cursorUp(up));
        f.append('\r');
        f.append(Ansi.ERASE_DISPLAY_TO_END);
    }

    /**
     * Paint one row from column 0, clipped so it cannot wrap, and advance a line. Returns the
     * physical rows occupied (1 unless clipping failed and the terminal width wrapped the row).
     */
    private int emitLine(StringBuilder f, String line) {
        String painted = RenderContext.truncateVisible(line, colBudget());
        f.append('\r');
        f.append(painted);
        f.append(Ansi.ERASE_LINE_TO_END);
        f.append('\n');
        int vis = RenderContext.visibleWidth(painted);
        int width = Math.max(1, m.width);
        if (vis <= 0) return 1;
        return Math.max(1, (vis + width - 1) / width);
    }

    /** Columns one row may paint at the current terminal width. */
    private int colBudget() {
        return RenderContext.rowColumnBudget(m.width);
    }

    /**
     * Pin a module-scope caption into scrollback above the live plan region, then repaint chrome.
     * Must not be used for process/tool lines (those go through the peek ring).
     */
    void pinScopeCaption(String captionLine) {
        if (captionLine == null || captionLine.isEmpty()) return;
        synchronized (m.lock) {
            if (m.done || !m.animate || !m.planMode || !Theme.active().isAnsi()) return;
            syncSize();
            StringBuilder f = new StringBuilder(256);
            liftRegion(f);
            emitLine(f, captionLine);
            writeLiveRegion(f, liveRegionLines(renderChromeLines(m.width, m.elapsedMillis()), m.width));
            m.out.print(f.toString());
            m.out.flush();
        }
    }

    /**
     * Peek open: lift the live region, print buffered process lines into scrollback, paint rule +
     * wedge. Must hold {@code m.lock}.
     */
    void openPeekPaint() {
        if (!m.animate || !Theme.active().isAnsi()) return;
        syncSize();
        List<String> chrome = renderChromeLines(m.width, m.elapsedMillis());
        int budget = OutputWindow.displayBudget(m.height, chrome.size());
        // Uncommitted only: lines from an earlier open (dump or live appends) are already
        // permanent scrollback right above — re-dumping them duplicates.
        List<String> pane = m.pane.window().uncommittedForDisplay(budget);
        StringBuilder f = new StringBuilder(256);
        liftRegion(f);
        for (String line : pane) emitLine(f, line);
        m.pane.window().noteCommitted(pane.size());
        m.pane.window().markAllCommitted();
        writeLiveRegion(f, liveRegionLines(chrome, m.width));
        m.out.print(f.toString());
        m.out.flush();
    }

    /**
     * Peek close: leave committed process lines in scrollback; replace the dotted rule with a blank
     * separator (still between process output and the wedge) and repaint chrome. Must hold
     * {@code m.lock}. Caller has already {@link OutputWindow#hide()}'d.
     */
    void closePeekPaint() {
        if (!m.animate || !Theme.active().isAnsi()) return;
        syncSize();
        StringBuilder f = new StringBuilder(256);
        liftRegion(f);
        // liveRegionLines paints "" when peek is off but process lines were committed.
        writeLiveRegion(f, liveRegionLines(renderChromeLines(m.width, m.elapsedMillis()), m.width));
        m.out.print(f.toString());
        m.out.flush();
    }

    /**
     * Append one process line below existing output (above the live region), then repaint rule +
     * wedge. Does not redraw prior process lines. Must hold {@code m.lock}.
     */
    private void liftEmitRepaintLive(String text) {
        syncSize();
        StringBuilder f = new StringBuilder(256);
        liftRegion(f);
        emitLine(f, text);
        m.pane.window().noteCommitted(1);
        m.pane.window().markAllCommitted();
        List<String> chrome = renderChromeLines(m.width, m.elapsedMillis());
        writeLiveRegion(f, liveRegionLines(chrome, m.width));
        m.out.print(f.toString());
        m.out.flush();
    }

    /** Write live-region lines from the current cursor and update lastLines / linesDrawn. */
    private void writeLiveRegion(StringBuilder f, List<String> live) {
        int rows = 0;
        for (String line : live) rows += emitLine(f, line);
        m.lastLines = List.copyOf(live);
        m.linesDrawn = rows;
        paintedCols = m.width;
        appendTaskbar(f, m.elapsedMillis());
    }

    /** The OS taskbar is re-told the bar's percent on every frame; it keeps no state of its own. */
    private void appendTaskbar(StringBuilder f, long elapsedMillis) {
        long[] bd = m.displayBar(elapsedMillis);
        f.append(Osc.taskbarProgress(ProgressBar.percent(bd[0], bd[1])));
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
        if (m.pane.window().visible()) {
            live.add(OutputWindow.ruleLine(cols));
        } else if (m.pane.window().committedScrollbackLines() > 0) {
            live.add(""); // blank stand-in for the rule
        }
        live.addAll(chrome);
        int maxRegion = OutputWindow.maxRegionLines(m.height);
        if (live.size() > maxRegion) {
            // Overflow drops TREE rows, never the separator or the header: a plain tail-slice
            // deleted the rule/blank first (violating the invariant above) and, one more over,
            // the spinner header too. Keep separator rows + chrome head, then fill
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
    private void syncSize() {
        Size.Window size = Size.current();
        int cols = size.cols() > 0 ? size.cols() : m.width;
        int rows = size.rows() > 0 ? size.rows() : m.height;
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
            up = Math.max(TerminalReflow.physicalRows(m.lastLines, fromCols, toCols), up);
        }
        if (up > 0) m.out.print(Ansi.cursorUp(up));
        m.out.print('\r');
        m.out.print(Ansi.ERASE_DISPLAY_TO_END);
        m.lastLines = List.of();
        m.linesDrawn = 0;
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
        Style dim = Theme.active().darkGray();
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
        if (m.model.completedCount() > 0 && rowsAfterHeader > 1) {
            completionSlots = Math.min(JkManager.MAX_COMPLETIONS + 1, Math.max(1, rowsAfterHeader / 2));
        }
        int budget = Math.max(1, rowsAfterHeader - completionSlots);
        List<PlanModel.TreeEntry> visible = collectVisibleTree();
        Tree work = Tree.untitled().gap(Tree.Gap.NONE);
        int shown = 0;
        for (int i = 0; i < visible.size() && budget > 0; i++) {
            PlanModel.TreeEntry entry = visible.get(i);
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

        if (m.model.completedCount() > 0) {
            int room = Math.max(0, rowsAfterHeader - (chrome.size() - 1));
            if (room > 0) {
                boolean overflow = m.model.completedCount() > Math.min(JkManager.MAX_COMPLETIONS, room);
                int cap = Math.max(0, Math.min(JkManager.MAX_COMPLETIONS, overflow ? room - 1 : room));
                int have = m.model.recentCompletions().size();
                int compShown = Math.min(have, cap);
                for (int i = 0; i < compShown; i++) {
                    chrome.add("    " + m.model.recentCompletions().get(have - 1 - i));
                }
                int more = m.model.completedCount() - compShown;
                if (more > 0) {
                    chrome.add(Theme.colorize("      … plus " + more + " more …", dim.italic()));
                }
            }
        }
        return chrome;
    }

    /** Running/failed tree entries, newest first. Module rows when available; else preflight phases. */
    private List<PlanModel.TreeEntry> collectVisibleTree() {
        List<PlanModel.TreeEntry> entries = new ArrayList<>();
        List<PlanModel.Row> active = new ArrayList<>();
        List<PlanModel.Row> failed = new ArrayList<>();
        for (PlanModel.Row r : m.model.rows().values()) {
            if (r.state == PlanModel.RowState.ACTIVE) active.add(r);
            else if (r.state == PlanModel.RowState.FAILED) failed.add(r);
        }
        if (!active.isEmpty() || !failed.isEmpty()) {
            for (int i = active.size() - 1; i >= 0; i--) entries.add(treeEntryForRow(active.get(i)));
            failed.sort((a, b) -> Long.compare(b.seq, a.seq));
            for (PlanModel.Row r : failed) entries.add(treeEntryForRow(r));
            return entries;
        }
        for (int i = m.model.phaseOrder().size() - 1; i >= 0; i--) {
            PlanModel.PhaseNode n = m.model.phases().get(m.model.phaseOrder().get(i));
            if (n != null && (n.state == PlanModel.PhaseState.RUNNING || n.state == PlanModel.PhaseState.FAILED)) {
                entries.add(treeEntryForPhase(n));
            }
        }
        return entries;
    }

    private PlanModel.TreeEntry treeEntryForRow(PlanModel.Row r) {
        boolean failed = r.state == PlanModel.RowState.FAILED;
        String brief = failed ? r.briefError : "";
        if ((brief == null || brief.isEmpty()) && failed) {
            PlanModel.PhaseNode n = m.model.phases().get(r.phase);
            if (n != null) brief = n.briefError;
        }
        String detail = failed ? "" : JkManagerColor.detailForDisplay(r.module, r.message);
        return new PlanModel.TreeEntry(
                renderWorkRow(r.module, JkManagerColor.phaseLabel(r.phase), failed, detail),
                brief == null ? "" : brief);
    }

    private PlanModel.TreeEntry treeEntryForPhase(PlanModel.PhaseNode n) {
        boolean failed = n.state == PlanModel.PhaseState.FAILED;
        String phaseLabel = n.label == null || n.label.isEmpty() ? JkManagerColor.phaseLabel(n.key) : n.label;
        String detail = failed ? "" : (n.detail == null ? "" : n.detail);
        return new PlanModel.TreeEntry(renderWorkRow("", phaseLabel, failed, detail), failed ? n.briefError : "");
    }

    /** Dim segment separator between module, phase, and detail on a work row. */
    private static final String WORK_ROW_SEP = "›";

    private String renderWorkRow(String module, String displayPhase, boolean failed, String detail) {
        Theme t = Theme.active();
        String icon;
        Style phaseStyle;
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
            sb.append(' ').append(sep).append(' ').append(JkManagerColor.colorDetail(phase, detail, t));
        }
        return sb.toString();
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
        // m.header.denominator()/m.header.solveLabel() on plain JMM visibility.
        long den;
        String sl;
        synchronized (m.lock) {
            den = m.header.denominator();
            sl = m.header.solveLabel();
        }
        boolean hasBar = barDen > 0 || den > 0;
        boolean phase1 = !hasBar && !sl.isEmpty();
        long elapsedSec = Math.max(0L, elapsedMillis) / 1000L;
        Countdown.Face face;
        synchronized (m.lock) {
            face = m.header.countdown.face(elapsedMillis);
        }
        RichText clock = clockFace(face, elapsedSec);
        RenderContext ctx = frameCtx.withCaps(m.nerdFont).withFrame(m.frame);
        if (phase1) {
            RichText msg = RichText.of(
                    RichText.ansi(Theme.colorize(sl, Theme.active().brightWhite())), RichText.plain(" "), clock);
            return new JkWedge(Icon.spinner(), m.name, msg)
                    .variant(JkWedge.Variant.WORK)
                    .renderLiveLine(ctx);
        }
        return new JkWedge(Icon.spinner(), m.name, RichText.empty())
                .variant(JkWedge.Variant.WORK)
                .progress(new Progress(hasBar ? barNum : 0, hasBar ? Math.max(1, barDen) : 0).suffix(clock))
                .renderLiveLine(ctx);
    }

    private RichText clockFace(Countdown.Face face, long elapsedSec) {
        String elapsed = DurationText.clock(elapsedSec);
        if (!face.seeded()) {
            return RichText.parse("[dark-gray]·[/] [mid-gray]" + elapsed + "[/]");
        }
        // Countdown stays mid-gray: ~remaining, then 0s, then +overrun. Elapsed stays dim, no +.
        String rem;
        if (face.remainingSec() > 0) {
            rem = "[mid-gray]~" + DurationText.clock(face.remainingSec()) + "[/]";
        } else if (face.overrunSec() > 0) {
            rem = "[mid-gray]+" + DurationText.clock(face.overrunSec()) + "[/]";
        } else {
            rem = "[mid-gray]0s[/]";
        }
        return RichText.parse(
                "[dark-gray]·[/] [dark-gray italic]ETA [/]" + rem + " [dark-gray]·[/] [dark-gray]" + elapsed + "[/]");
    }
}
