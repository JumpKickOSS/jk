// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.Osc;
import cc.jumpkick.cli.theme.StreamCaps;
import cc.jumpkick.config.TerminalIdentity;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.ProgramStatus;
import cc.jumpkick.terminal.ProgramStatus.Report;
import cc.jumpkick.terminal.ProgramStatus.Waiting;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The one place jk tells its terminal what it is doing: working (with a percentage when one is
 * known), waiting on the user, failed, done or idle. Every live region and prompt reports through
 * here, so a session speaks exactly one protocol: OSC 7501 when the terminal answers its query,
 * otherwise OSC 9;4.
 *
 * <p>Nothing is sent until a TUI surface {@linkplain #begin begins}. {@code done} and {@code
 * error} are sent only when the whole command ends ({@link #exit}); between steps the state stays
 * {@code working}. A report identical to the last one is not sent again.
 */
public final class TerminalStatus {

    /** The protocol a session speaks; {@link #NONE} sends nothing at all. */
    enum Protocol {
        NONE,
        OSC_7501,
        OSC_9_4
    }

    /** A detection's protocol, and whether the terminal itself was asked (an answer that holds). */
    record Detected(Protocol protocol, boolean probed) {}

    /** How long the probe waits for the replies before taking the query as unanswered. */
    static final Duration PROBE_WAIT = Duration.ofMillis(150);

    /** The quiet gap that ends one burst of reply bytes. */
    private static final Duration REPLY_GAP = Duration.ofMillis(20);

    private static final TerminalStatus PROCESS = new TerminalStatus(TerminalStatus::detect);

    private final Supplier<Detected> detect;
    private Protocol protocol = Protocol.NONE;
    private boolean probed;
    private @Nullable PrintStream out;
    private @Nullable String last;
    private Report current = Report.idle();
    private final Set<String> children = new LinkedHashSet<>();

    TerminalStatus(Supplier<Detected> detect) {
        this.detect = detect;
    }

    /** This process's reporter. */
    public static TerminalStatus get() {
        return PROCESS;
    }

    /**
     * A TUI surface takes the terminal and reports on {@code stream}. The terminal is asked at most
     * once per process; a different stream (a test's capture) starts with nothing known to be on
     * screen, and is checked again unless the terminal has answered.
     */
    public synchronized void begin(PrintStream stream) {
        if (stream == out) return;
        out = stream;
        last = null;
        if (!probed) {
            Detected d = detect.get();
            protocol = d.protocol();
            probed = d.probed();
        }
    }

    /** Whether a surface has begun and the terminal is told anything. */
    synchronized boolean reporting() {
        return out != null && protocol != Protocol.NONE;
    }

    /**
     * {@code report} encoded for the session's protocol, for a caller that writes it inside its own
     * frame; empty when nothing should be sent (not begun, unchanged, or not expressible).
     */
    public synchronized String encode(Report report) {
        if (!reporting()) return "";
        if (report.id() == null) {
            current = report;
        } else if (report.state() != ProgramStatus.State.CLEAR) {
            children.add(report.id());
        }
        String seq =
                switch (protocol) {
                    case OSC_7501 -> ProgramStatus.osc7501(report);
                    case OSC_9_4 -> ProgramStatus.osc94(report);
                    case NONE -> "";
                };
        if (seq.isEmpty() || seq.equals(last)) return "";
        last = seq;
        return seq;
    }

    /** Send {@code report} now, on the stream the session began on. */
    public synchronized void send(Report report) {
        String seq = encode(report);
        if (seq.isEmpty() || out == null) return;
        out.print(seq);
        out.flush();
    }

    /** A frame's report: working, {@code percent} done (0–100) or {@code -1} when unknown. */
    public String working(int percent) {
        return encode(percent < 0 ? Report.working() : Report.working(percent));
    }

    /**
     * What a live region writes as it ends while the command goes on: still working, with no
     * percentage.
     */
    public String between() {
        return encode(Report.working());
    }

    /**
     * Report the session blocked on the user until the returned handle closes, then put back what
     * it reported before. A prompt on a real terminal begins the session on {@code stream} when no
     * surface has.
     */
    public Blocked blocked(Waiting kind, PrintStream stream) {
        Report before;
        synchronized (this) {
            if (out == null && StreamCaps.live(StreamCaps.Side.OUT)) begin(stream);
            before = current;
        }
        send(Report.blocked(kind));
        return () -> send(before);
    }

    /** The handle {@link #blocked} returns; closing it ends the wait. */
    public interface Blocked extends AutoCloseable {
        @Override
        void close();
    }

    /** The user cancelled: child records go, and the session is idle. */
    public synchronized void cancelled() {
        clearChildren();
        send(Report.idle());
    }

    /**
     * The command ends with {@code exitCode}: child records go, then {@code done} on success,
     * {@code idle} when a signal or the user cancelled it, {@code error} otherwise. Nothing when no
     * surface began.
     */
    public synchronized void exit(int exitCode) {
        if (!reporting()) return;
        clearChildren();
        send(finalReport(exitCode));
    }

    /** The report a command ending with {@code exitCode} leaves behind. */
    static Report finalReport(int exitCode) {
        if (exitCode == Exit.SUCCESS) return Report.done();
        boolean cancelled =
                exitCode == Exit.INTERRUPTED || exitCode == GlobalCancel.TERMINATED || exitCode == GlobalCancel.HUNG_UP;
        return cancelled ? Report.idle() : Report.error();
    }

    /** One clear per top-level parent removes every child record beneath it. */
    private void clearChildren() {
        Set<String> parents = new LinkedHashSet<>();
        for (String id : children) {
            int slash = id.indexOf('/');
            parents.add(slash < 0 ? id : id.substring(0, slash));
        }
        children.clear();
        for (String parent : parents) send(Report.clear(parent));
    }

    /**
     * The real terminal's protocol. Nothing when OSC output is off; the probe only when stdin and
     * stdout are both a terminal and no multiplexer sits in between (tmux and screen have no
     * passthrough that clears properly); OSC 9;4 otherwise.
     */
    private static Detected detect() {
        if (!Osc.oscEnabled()) return new Detected(Protocol.NONE, false);
        boolean askable =
                askable(StreamCaps.live(StreamCaps.Side.OUT), Terminals.stdinIsTty(), TerminalIdentity.current());
        TerminalSession tty = askable ? Terminals.controlling() : null;
        if (tty == null || !tty.isLive()) return new Detected(Protocol.OSC_9_4, false);
        return new Detected(probe(tty, PROBE_WAIT), true);
    }

    /**
     * Whether the terminal may be asked: stdout and stdin both a terminal, and no multiplexer in
     * between to swallow or misroute the replies.
     */
    static boolean askable(boolean stdoutLive, boolean stdinTty, TerminalIdentity identity) {
        return stdoutLive && stdinTty && !identity.multiplexed();
    }

    /**
     * Ask {@code tty} for OSC 7501, then for its device attributes, and read the replies for up to
     * {@code wait}. The 7501 reply arriving first means OSC 7501; the attributes first, or nothing
     * in time, means OSC 9;4.
     */
    static Protocol probe(TerminalSession tty, Duration wait) {
        try (var mode = tty.enter(InputMode.PROMPT)) {
            PrintWriter w = tty.ttyOut();
            w.print(ProgramStatus.QUERY + ProgramStatus.DEVICE_ATTRIBUTES);
            w.flush();
            StringBuilder replies = new StringBuilder();
            long deadline = Clock.SYSTEM.nanos() + wait.toNanos();
            // Read on until the device attributes arrive, so their reply is never left behind as keys.
            while (!ProgramStatus.answeredAttributes(replies.toString())) {
                long left = deadline - Clock.SYSTEM.nanos();
                if (left <= 0) break;
                String chunk = tty.readReply(Duration.ofNanos(left), REPLY_GAP);
                if (chunk.isEmpty()) break;
                replies.append(chunk);
            }
            return ProgramStatus.answer(replies.toString()) == ProgramStatus.Answer.SUPPORTED
                    ? Protocol.OSC_7501
                    : Protocol.OSC_9_4;
        } catch (RuntimeException e) {
            return Protocol.OSC_9_4;
        }
    }
}
