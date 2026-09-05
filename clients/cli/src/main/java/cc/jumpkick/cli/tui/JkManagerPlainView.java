// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.DurationText;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TestFailureInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --no-ansi} renderer for {@link JkManager}: append-only {@code jk: * …} lines instead of
 * a repainted region. Sibling of {@link JkManagerView} on the mode axis, not a layer under it —
 * exactly one of the two draws a given region, chosen by {@link #animating()}, and neither calls the
 * other except where the two modes converge on a settle line.
 *
 * <p>Invariant: <b>cadence</b>. A plain build must print on stage changes, module {@code built}, a
 * {@link #HEARTBEAT_MS} heartbeat while one stage runs long, and the final {@code done} — and must
 * <em>not</em> print on percent ticks, of which a build emits thousands. Every field below exists to
 * decide "has this already been said?", so they move together: {@link #lastSubject} /
 * {@link #lastStatus} suppress the repeat, {@link #lastPrintedNanos} arms the heartbeat that
 * deliberately bypasses that suppression, and {@link #chromeStarted} / {@link #progressStarted}
 * order the one mandatory opening line against the one mandatory closing line. Splitting them across
 * two types is what lets a build print the same line twice, or fall silent for ten minutes.
 *
 * <p>The vocabulary of the status words themselves ({@code compiling}, {@code running 80 tests}) is
 * {@link PlainPhase}'s; this type owns only when a line is emitted and how it is composed.
 */
@NullMarked
final class JkManagerPlainView {

    /** Long-stage heartbeat: reprint the current status after this much silence. */
    static final long HEARTBEAT_MS = 30_000L;

    private final JkManager m;

    /**
     * Multi-line progress: false until the first prepare/progress line. Mid-run lines print on stage
     * changes, the heartbeat while a stage is active, module {@code built}, and settle {@code done}.
     */
    private boolean progressStarted;

    /** True after any plain working/progress line has been printed for this region. */
    private boolean chromeStarted;

    /** True when aggregate progress (den &gt; 0) drove plain chrome — settle uses 100% done. */
    private boolean progressMode;

    /** True after the first plain line that included a known ETA. */
    private boolean etaAnnounced;

    /** Last printed subject + status — suppress same-phase reprints (the heartbeat forces). */
    private String lastSubject = "";

    private String lastStatus = "";

    /**
     * {@link System#nanoTime()} of the last progress line; 0 before any line. Package-private:
     * JkManagerPlainProgressTest rewinds it to simulate a silent stage.
     */
    long lastPrintedNanos;

    JkManagerPlainView(JkManager m) {
        this.m = m;
    }

    /**
     * True when this region renders plain chrome: animating with ANSI promised away
     * ({@code --no-ansi}, {@code TERM=dumb}, CI). The single mode test — callers state what
     * happened and this decides whether it is said out loud.
     */
    boolean animating() {
        return m.animate && !Theme.active().isAnsi();
    }

    /**
     * Ensure the first prepare/progress line exists once aggregate progress is known. Must hold the
     * manager lock.
     */
    void ensureProgressStarted(long num, long den) {
        if (m.done || !animating() || den <= 0 || progressStarted) return;
        progressMode = true;
        printInitializing();
        String status = currentStatus();
        String subject = currentSubject();
        if (PlainPhase.PREPARE.equals(status) || firstActiveRow() == null) {
            status = PlainPhase.PREPARE;
            if (subject.isEmpty()) subject = m.planCoord == null ? "" : m.planCoord;
        }
        printSnapshot(0, status, false, subject, "");
    }

    /**
     * Reprint the current stage when it has been silent for {@link #HEARTBEAT_MS}. Must hold the
     * manager lock. Refreshes percent, ETA, and live status details (e.g. remaining tests).
     */
    void maybeEmitHeartbeat() {
        if (m.done || !animating() || !progressMode || !progressStarted) return;
        if (lastPrintedNanos == 0) return;
        long elapsedNs = System.nanoTime() - lastPrintedNanos;
        if (elapsedNs < HEARTBEAT_MS * 1_000_000L) return;
        if (firstActiveRow() == null) return;
        String status = currentStatus();
        if (PlainPhase.PREPARE.equals(status)
                || PlainPhase.DONE.equals(status)
                || PlainPhase.BUILT.equals(status)
                || PlainPhase.INITIALIZING.equals(status)) {
            return;
        }
        // Same gate as phase-change: wait for "compiling N sources" / "running N tests".
        if ("compiling".equals(status) || PlainPhase.RUNNING_TESTS.equals(status)) return;
        printSnapshot(currentPercent(), status, false, currentSubject(), "", true);
    }

    /** First ETA seed: print immediately as {@code start} on the workspace coordinate. */
    void emitEtaKnown() {
        if (m.done || !animating()) return;
        if (etaAnnounced) return;
        progressMode = true;
        printInitializing();
        String root = planCoordOrEmpty();
        if (!PlainPhase.PREPARE.equals(lastStatus) && firstActiveRow() == null && !root.isEmpty()) {
            printSnapshot(0, PlainPhase.PREPARE, false, root, "");
        }
        etaAnnounced = true;
        printSnapshot(currentPercent(), PlainPhase.START, false, root, "");
    }

    /** Major step/phase change: print immediately at the current percent. */
    void emitPhaseChange() {
        if (m.done || !animating()) return;
        progressMode = true;
        printInitializing();
        if (m.header.countdown.seeded()) etaAnnounced = true;
        String status = currentStatus();
        if (PlainPhase.PREPARE.equals(status)) return;
        // Wait for "compiling N sources" / "running N tests" before the first line of those phases.
        if ("compiling".equals(status) || PlainPhase.RUNNING_TESTS.equals(status)) return;
        printSnapshot(currentPercent(), status, false);
    }

    /** Module finished: print immediately with {@code built}. */
    void emitModuleBuilt(String module) {
        if (m.done || !animating()) return;
        progressMode = true;
        printInitializing();
        String subject = module == null ? "" : module;
        printSnapshot(currentPercent(), PlainPhase.BUILT, false, subject, "");
    }

    /**
     * A running step's message changed. Records the plain status override the countdown reads
     * ({@code running 80 tests} / {@code compiling 12 sources}) and prints the stage change. Must
     * hold the manager lock.
     */
    void onStepMessage(PlanModel.Row r) {
        if (!animating()) return;
        if (r.message.contains("classpath input size")) emitStepDetail(r.message);
        var tests = PlainPhase.runningTestsCount(r.message);
        if (tests.isPresent()) {
            r.plainRemainingTests = tests.get();
            r.plainStatusOverride = PlainPhase.runningTests(r.plainRemainingTests);
            emitPhaseChange();
            return;
        }
        var compile = PlainPhase.compileSourcesStatus(r.message);
        if (compile.isPresent()) {
            r.plainStatusOverride = compile.get();
            emitPhaseChange();
        }
    }

    /**
     * A static test finished: decrement the remaining-test count without printing — the next
     * stage-change or heartbeat line shows the updated {@code running N tests}. Must hold the
     * manager lock.
     */
    void noteTestTick(PlanModel.Row r, int delta) {
        if (r.plainRemainingTests < 0) return;
        r.plainRemainingTests = Math.max(0, r.plainRemainingTests - Math.max(0, delta));
        r.plainStatusOverride = PlainPhase.runningTests(r.plainRemainingTests);
    }

    /**
     * Verbose-only sub-step detail (e.g. native classpath size). Same module/phase as the last line
     * is allowed because the detail is the point.
     */
    void emitStepDetail(String detail) {
        if (m.done || !animating()) return;
        if (!SessionContext.current().config().verboseOr(false)) return;
        String extra = detail == null ? "" : PlainAscii.transform(detail);
        if (extra.isBlank()) return;
        progressMode = true;
        printInitializing();
        printSnapshot(currentPercent(), currentStatus(), false, currentSubject(), extra);
    }

    private void printInitializing() {
        if (chromeStarted || m.done) return;
        m.out.println(JkWedge.plainWedge(Glyphs.PULSE_PLAIN, m.planName(), PlainPhase.INITIALIZING));
        chromeStarted = true;
        m.out.flush();
    }

    /** Indeterminate start/heartbeat (simple mode open, or plan without progress). */
    void printIndeterminate(boolean forceStart) {
        if (!animating()) return;
        synchronized (m.lock) {
            if (m.done) return;
            if (chromeStarted && !forceStart) return;
            if (chromeStarted && progressMode) return;
            m.out.println(indeterminateLine(false));
            chromeStarted = true;
            m.out.flush();
        }
    }

    /** Mandatory done line — progress ends at {@code 100% - done}. */
    void printDone() {
        if (!animating()) return;
        if (progressMode) {
            if (!progressStarted) {
                printInitializing();
            }
            printSnapshot(100, PlainPhase.DONE, true, planCoordOrEmpty(), "");
            return;
        }
        if (chromeStarted || !m.planMode) {
            if (!chromeStarted) {
                m.out.println(indeterminateLine(false));
            }
            m.out.println(indeterminateLine(true));
            chromeStarted = true;
            m.out.flush();
        }
    }

    /**
     * There is no Ctrl-O listener and no settle dump in plain mode, so a tool/worker crash would
     * leave its only evidence (the buffered stdout ring) invisible. Print the uncommitted ring lines
     * sequentially — they land directly above the failure wedge the settle is about to emit.
     */
    void dumpProcessOutput() {
        synchronized (m.lock) {
            var pending = m.pane.window().uncommittedForDisplay(OutputWindow.MAX_LINES);
            for (String line : pending) m.out.println(line);
            m.pane.window().markAllCommitted();
            if (!pending.isEmpty()) m.out.flush();
        }
    }

    /**
     * {@code "jk: * Format > Examining source files :: 10% - prepare"} or {@code coord :: 100% - done}.
     */
    static String progressLine(String command, String message, int percent, boolean done) {
        String subject = (message == null || message.isBlank()) ? "" : message;
        if (done) {
            return JkWedge.plainWedge(
                    Glyphs.PULSE_PLAIN,
                    command == null ? "" : command,
                    formatTail(subject, 100, null, PlainPhase.DONE, ""));
        }
        return JkWedge.plainWedge(
                Glyphs.PULSE_PLAIN,
                command == null ? "" : command,
                formatTail(subject, percent, null, PlainPhase.PREPARE, ""));
    }

    private void printSnapshot(int percent, String status, boolean doneLine) {
        printSnapshot(percent, status, doneLine, currentSubject(), "", false);
    }

    private void printSnapshot(int percent, String status, boolean doneLine, String subject, String detail) {
        printSnapshot(percent, status, doneLine, subject, detail, false);
    }

    private void printSnapshot(
            int percent, String status, boolean doneLine, String subject, String detail, boolean force) {
        String line = doneLine
                ? JkWedge.plainWedge(
                        Glyphs.PULSE_PLAIN, m.planName(), formatTail(subject, 100, null, PlainPhase.DONE, ""))
                : JkWedge.plainWedge(
                        Glyphs.PULSE_PLAIN, m.planName(), formatTail(subject, percent, etaClock(), status, detail));
        if (!doneLine && !force && !shouldPrint(status, subject, detail)) return;
        lastSubject = subject == null ? "" : subject;
        lastStatus = status == null ? "" : status;
        m.out.println(line);
        chromeStarted = true;
        progressStarted = true;
        lastPrintedNanos = System.nanoTime();
        m.out.flush();
    }

    /**
     * Print on a new module/phase ({@code start}/{@code built}/{@code prepare}/…), or a verbose
     * detail. Same module+phase with only an ETA/percent tick is suppressed; the heartbeat bypasses
     * this via {@code force}.
     */
    private boolean shouldPrint(String status, String subject, String detail) {
        if (detail != null && !detail.isBlank()) return true;
        String sub = subject == null ? "" : subject;
        String st = status == null ? "" : status;
        return !sub.equals(lastSubject) || !PlainPhase.sameFamily(st, lastStatus);
    }

    private static String formatTail(String subject, int percent, @Nullable String eta, String status, String detail) {
        String st = status == null || status.isBlank() ? PlainPhase.PREPARE : status;
        StringBuilder tail = new StringBuilder();
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
        // The subject joins through the label owner so the separator cannot drift (blank drops it).
        return TestFailureInfo.label(subject, tail.toString(), 0);
    }

    private String planCoordOrEmpty() {
        return m.planCoord == null ? "" : m.planCoord;
    }

    /** {@code "jk: * Format > Examining source files - working..."} / {@code … - done.}. */
    static String indeterminateLine(String command, String message, boolean done) {
        if (command == null || command.isEmpty()) {
            return JkWedge.plainStatusLine(null, message, done ? JkWedge.PlainTail.DONE : JkWedge.PlainTail.WORKING);
        }
        if (done) {
            return JkWedge.plainStatusLine(command, null, JkWedge.PlainTail.PERCENT_DONE);
        }
        String msg = (message == null || message.isBlank()) ? PlainPhase.INITIALIZING : message;
        return JkWedge.plainStatusLine(command, msg, JkWedge.PlainTail.BARE);
    }

    private String indeterminateLine(boolean doneLine) {
        if (m.planMode) {
            return indeterminateLine(m.planName(), doneLine ? null : PlainPhase.INITIALIZING, doneLine);
        }
        return indeterminateLine(null, m.label, doneLine);
    }

    private String currentStatus() {
        PlanModel.Row active = firstActiveRow();
        if (active != null) {
            if (active.plainStatusOverride != null && !active.plainStatusOverride.isEmpty()) {
                return active.plainStatusOverride;
            }
            if (active.plainRemainingTests >= 0) {
                return PlainPhase.runningTests(active.plainRemainingTests);
            }
            return PlainPhase.status(active.phase != null ? active.phase : active.step);
        }
        String sl = m.header.solveLabel();
        if (sl != null && !sl.isEmpty()) return PlainPhase.status(sl);
        return PlainPhase.PREPARE;
    }

    private String currentSubject() {
        PlanModel.Row active = firstActiveRow();
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
    private PlanModel.@Nullable Row firstActiveRow() {
        PlanModel.Row last = null;
        for (PlanModel.Row r : m.model.rows().values()) {
            if (r.state == PlanModel.RowState.ACTIVE) last = r;
        }
        return last;
    }

    private int currentPercent() {
        long[] bd = m.displayBar(m.elapsedMillis());
        if (bd[1] <= 0) return 0;
        return new Progress(bd[0], bd[1]).percent();
    }

    private @Nullable String etaClock() {
        long remMs = m.header.countdown.remainingMs(m.elapsedMillis());
        if (remMs < 0) return null;
        return DurationText.clockMillis(remMs);
    }
}
