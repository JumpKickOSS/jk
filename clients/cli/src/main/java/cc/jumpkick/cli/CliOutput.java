// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.tui.PlainAscii;
import cc.jumpkick.terminal.Terminals;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The CLI's single user-facing output seam. Commands write results and diagnostics through here
 * rather than touching {@link System#out}/{@link System#err} directly, so there is one place that
 * owns "what the client prints" — the client-side counterpart to the engine routing everything
 * through {@code BuildPlanListener}/{@code TaskContext}.
 *
 * <p>Convention, matching the streams it wraps: {@link #out} for machine/user <em>result</em> output
 * on stdout; {@link #err} for human-facing diagnostics, errors, and progress on stderr.
 *
 * <h2>Blank-line envelope</h2>
 *
 * <p>Human commands print exactly one blank line before the first chrome of the invocation and one
 * blank line after the last. This class owns that rule: the first {@link #out}/{@link #err}/{@link
 * #stdout}/{@link #stderr} write inserts the leading blank; dispatch calls {@link #beginCommand}
 * before {@code run} and {@link #closeEnvelope} after it. The trailing blank lands on whichever
 * stream wrote last, so a command that ends on a stderr failure gets its gap there and a redirected
 * stdout stays clean. Settles themselves never close.
 *
 * <h2>Script mode</h2>
 *
 * <p>When this invocation's stdout is consumed by a program (an {@code eval}'d script, a path, a
 * token, wire JSON) dispatch begins the command in script mode: no leading or trailing blank on
 * stdout and no {@link PlainAscii} rewrite of it, so the payload is byte-exact. stderr keeps full
 * human treatment — it is for the person at the terminal even when stdout is piped. Commands
 * declare the mode via {@code CliCommand.scriptMode(Invocation)}; {@code --output json}/{@code
 * jsonl} enters it too.
 *
 * <h2>Encoding</h2>
 *
 * <p>{@link #out}/{@link #err} and their raw forms hand {@link System#out}/{@link System#err} a
 * string, so that stream's own encoder produces the bytes: the console's on a terminal, a
 * redirect's under capture. {@link #stdout()}/{@link #stderr()} write finished bytes <em>past</em>
 * that encoder, so they encode with the charset of the very stream they were opened over rather
 * than with a system property. The bytes therefore cannot disagree with {@link System#out} however
 * it was installed — a Windows UTF-8 console bootstrap, {@code JkManager.captureOutput}, a test
 * harness.
 *
 * <h2>Handing the terminal to a child</h2>
 *
 * <p>{@link #handOffTerminal(ProcessBuilder)} is the one handoff: it restores the terminal out of
 * jk's own mode and starts the child on inherited stdio. The leading blank is already printed by
 * then, so the child's first line is separated from jk's chrome. The <em>trailing</em> blank would
 * land after the child's own output, in the caller's redirect — commands whose child has the last
 * word also call {@link #skipTrailingBlank()}; the ones that keep printing afterwards do not.
 */
public final class CliOutput {

    /**
     * Once true, the leading blank has been printed for this command. Reset at dispatch so prep
     * spinners and settle chips share one envelope.
     */
    private static final AtomicBoolean ENVELOPE_STARTED = new AtomicBoolean(false);

    /** Once true, {@link #closeEnvelope} has printed the trailing blank. */
    private static final AtomicBoolean ENVELOPE_CLOSED = new AtomicBoolean(false);

    /** When true, stdout is machine-consumed: no envelope and no ASCII rewrite on that stream. */
    private static final AtomicBoolean SCRIPT_MODE = new AtomicBoolean(false);

    /**
     * The side the most recent chrome write went to. The trailing blank follows it, so the gap
     * lands after the last visible line rather than on a stream the user redirected away.
     */
    private static final AtomicBoolean LAST_WRITE_ON_ERR = new AtomicBoolean(false);

    /** When true, a child process owns the terminal and {@link #closeEnvelope} must stay quiet. */
    private static final AtomicBoolean TRAILING_BLANK_SKIPPED = new AtomicBoolean(false);

    private CliOutput() {}

    /**
     * Start one leaf command's blank-line envelope, clearing all per-command state. {@code
     * scriptMode} marks this invocation's stdout machine-consumed: no leading or trailing blank on
     * stdout and no {@link PlainAscii} rewrite of it, so the payload is byte-exact; stderr stays
     * human-formatted either way. Called from command dispatch before {@code run} and from nothing
     * else.
     */
    public static void beginCommand(boolean scriptMode) {
        ENVELOPE_STARTED.set(false);
        ENVELOPE_CLOSED.set(false);
        LAST_WRITE_ON_ERR.set(false);
        TRAILING_BLANK_SKIPPED.set(false);
        SCRIPT_MODE.set(scriptMode);
    }

    /** True when this invocation's stdout is machine-consumed (see {@link #beginCommand}). */
    public static boolean scriptMode() {
        return SCRIPT_MODE.get();
    }

    /**
     * Per-command flags {@link #beginCommand} owns. Captured/restored by the test boundary so a
     * script-mode leaf (or {@code --output json}) cannot leave {@link #scriptMode()} true for the
     * next class in the same worker — Spinner and JdkDownloadBar treat that flag as silence.
     */
    record State(
            boolean scriptMode,
            boolean envelopeStarted,
            boolean envelopeClosed,
            boolean lastWriteOnErr,
            boolean trailingBlankSkipped) {}

    /** Snapshot for {@code CliOutputGlobal}; package-private so only the same-package boundary sees it. */
    static State captureState() {
        return new State(
                SCRIPT_MODE.get(),
                ENVELOPE_STARTED.get(),
                ENVELOPE_CLOSED.get(),
                LAST_WRITE_ON_ERR.get(),
                TRAILING_BLANK_SKIPPED.get());
    }

    /** Put back whatever {@link #captureState()} returned. */
    static void restoreState(State state) {
        SCRIPT_MODE.set(state.scriptMode());
        ENVELOPE_STARTED.set(state.envelopeStarted());
        ENVELOPE_CLOSED.set(state.envelopeClosed());
        LAST_WRITE_ON_ERR.set(state.lastWriteOnErr());
        TRAILING_BLANK_SKIPPED.set(state.trailingBlankSkipped());
    }

    /** True after the leading blank has been printed for this command. */
    public static boolean envelopeStarted() {
        return ENVELOPE_STARTED.get();
    }

    /**
     * Mark the envelope as already opened without printing. Use when chrome that owns its own
     * leading blank (wizard header, terminal writer) ran first, so later writes do not insert a
     * second blank. No-op in script mode.
     */
    public static void markEnvelopeStarted() {
        if (SCRIPT_MODE.get()) return;
        ENVELOPE_STARTED.set(true);
    }

    /** Leading blank on stdout — at most once per command. No-op in script mode. */
    public static void ensureLeadingBlank() {
        openEnvelope(System.out, false);
    }

    /** Leading blank on stderr — at most once per command (failure / working chrome). */
    public static void ensureLeadingBlankErr() {
        openEnvelope(System.err, true);
    }

    /**
     * Leading blank on a <em>stdout-side</em> stream this class does not own: a {@link
     * cc.jumpkick.cli.tui.JkManager} sink, a live terminal writer, a test capture. Shares the
     * per-command flag with {@link #out}. For stderr chrome call {@link #ensureLeadingBlankErr()} —
     * a wrapper stream cannot be recognised by reference, so this overload does not guess.
     */
    public static void ensureLeadingBlank(PrintStream dest) {
        openEnvelope(dest, false);
    }

    /**
     * A child process owns the terminal from here on ({@code ProcessBuilder.inheritIO}): suppress
     * the envelope's trailing blank only. Everything already printed stays. Without this, {@code jk
     * run > app.out} gains a trailing newline the program never emitted.
     */
    public static void skipTrailingBlank() {
        TRAILING_BLANK_SKIPPED.set(true);
    }

    /**
     * Hand this terminal to a child and start it: take the terminal out of whatever mode jk put it
     * in, then {@code inheritIO().start()}. The <strong>one</strong> handoff — the two steps are a
     * pair and were being spelled separately at ten sites, three of which
     * ({@code jk gradle}, {@code jk mvn}, {@code jk self update}'s engine takeover) forgot the
     * restore, so an interactive child inherited jk's raw mode and its own line editing did not
     * work.
     *
     * <p>{@link #skipTrailingBlank()} is deliberately <em>not</em> folded in, even though four call
     * sites pair it with the handoff verbatim. It answers a different question — whether jk prints
     * again after the child — and two callers do: {@code AppWatchLoop} keeps reporting restarts, and
     * {@code jk self update} announces the takeover once the new engine is up. Folding it in would
     * silently drop their closing blank. Call it at the handoff when the child's output is the last
     * thing on this terminal.
     */
    public static Process handOffTerminal(ProcessBuilder pb) throws IOException {
        Terminals.restoreForChild();
        return pb.inheritIO().start();
    }

    /**
     * Trailing blank after the last chrome line of a human command, on whichever stream wrote it
     * last, so the gap lands after the last visible line and a redirected stdout does not collect
     * it. Idempotent. No-op when nothing opened the envelope or after {@link #skipTrailingBlank()}.
     * Deliberately no script-mode check: machine stdout never opens the envelope, so the only thing
     * that can reach here in script mode is a stderr wedge, and that wedge has earned its gap.
     * Dispatch calls this after {@code run}; commands and settles do not.
     */
    public static void closeEnvelope() {
        if (TRAILING_BLANK_SKIPPED.get() || !ENVELOPE_STARTED.get()) return;
        if (!ENVELOPE_CLOSED.compareAndSet(false, true)) return;
        PrintStream dest = LAST_WRITE_ON_ERR.get() ? System.err : System.out;
        dest.println();
        dest.flush();
    }

    /**
     * Note a chrome write, printing the leading blank on the first one. {@code err} is the side
     * {@code dest} ultimately writes to; machine stdout is not chrome, so in script mode a stdout
     * write leaves the envelope untouched. Returns true when this call printed the blank, so a
     * caller whose own payload <em>is</em> a blank line does not emit a second one.
     */
    private static boolean openEnvelope(PrintStream dest, boolean err) {
        if (dest == null) return false;
        if (SCRIPT_MODE.get() && !err) return false;
        LAST_WRITE_ON_ERR.set(err);
        if (!ENVELOPE_STARTED.compareAndSet(false, true)) return false;
        dest.println();
        return true;
    }

    /** Rewrite {@code line} for plain consoles unless it is machine-bound stdout. */
    private static String render(String line, boolean err) {
        return err || !SCRIPT_MODE.get() ? PlainAscii.apply(line) : line;
    }

    /** Print a line to stdout (result output). */
    public static void out(String line) {
        if (line == null || line.isEmpty()) {
            out();
            return;
        }
        openEnvelope(System.out, false);
        System.out.println(render(line, false));
    }

    /** Print a blank line to stdout. The first blank of a command <em>is</em> the envelope. */
    public static void out() {
        if (openEnvelope(System.out, false)) return;
        System.out.println();
    }

    /** Print to stdout with no trailing newline. */
    public static void outRaw(String s) {
        openEnvelope(System.out, false);
        System.out.print(render(s, false));
    }

    /** Print a line to stderr (diagnostics, errors, progress). */
    public static void err(String line) {
        if (line == null || line.isEmpty()) {
            err();
            return;
        }
        openEnvelope(System.err, true);
        System.err.println(render(line, true));
    }

    /** Print a blank line to stderr. The first blank of a command <em>is</em> the envelope. */
    public static void err() {
        if (openEnvelope(System.err, true)) return;
        System.err.println();
    }

    /** Print to stderr with no trailing newline. */
    public static void errRaw(String s) {
        openEnvelope(System.err, true);
        System.err.print(render(s, true));
    }

    /**
     * Stdout for APIs that need a {@link PrintStream} (JkManager, Spinner, renderers). The first
     * write opens the envelope; string writes are ASCII-rewritten under plain mode unless stdout is
     * machine-consumed.
     */
    public static PrintStream stdout() {
        return EnvelopeStream.open(false);
    }

    /**
     * Stderr for APIs that need a {@link PrintStream}. The first write opens the envelope and
     * string writes are ASCII-rewritten under plain mode — stderr is human even in script mode.
     */
    public static PrintStream stderr() {
        return EnvelopeStream.open(true);
    }

    /**
     * True when {@code buf[off,len]} is itself a line break: a run of {@code \n} / {@code \r}
     * carrying at least one {@code \n}. Such a write already <em>is</em> the leading blank, so the
     * first-write hook must not print a second one. A bare {@code \r} is a cursor return (a live
     * region repainting a row in place), not a blank line.
     */
    static boolean isLineBreak(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) return false;
        int end = Math.min(off + len, buf.length);
        boolean sawNewline = false;
        for (int i = Math.max(off, 0); i < end; i++) {
            byte c = buf[i];
            if (c == '\n') sawNewline = true;
            else if (c != '\r') return false;
        }
        return sawNewline;
    }

    /**
     * The stream behind {@link #stdout()} / {@link #stderr()}. Rewrites Unicode chrome to ASCII
     * under plain mode (never for machine stdout), opens the envelope on the first write, and
     * encodes with the charset of the stream it writes to. One stream does all three so every
     * {@link PrintStream} path shares one hook and there is exactly one encoder in the chain.
     */
    private static final class EnvelopeStream extends PrintStream implements PlainAscii.Rewriting {
        private final boolean err;

        /**
         * Takes sink and charset from one and the same {@link System#out}/{@link System#err}
         * observation. These bytes reach that stream already encoded, so reading the charset off
         * anything else — a property, a later {@code System.setOut} — is how a UTF-8 console ends
         * up printing OEM bytes. A factory because Java forbids statements before {@code super(…)}.
         */
        private static EnvelopeStream open(boolean err) {
            LiveSystemStream sink = new LiveSystemStream(err);
            return new EnvelopeStream(sink, sink.target.charset(), err);
        }

        private EnvelopeStream(LiveSystemStream sink, Charset cs, boolean err) {
            super(sink, true, cs);
            this.err = err;
        }

        @Override
        public void print(String s) {
            super.print(render(s, err));
        }

        @Override
        public void println(String s) {
            print(s); // subclass path: print + newline, so the rewrite runs once
            println();
        }
    }

    /** Byte sink for {@link EnvelopeStream}: the envelope hook plus a fixed target stream. */
    private static final class LiveSystemStream extends OutputStream {
        private final PrintStream target;
        private final boolean err;

        /**
         * Snapshots the stream at construction: a {@code JkManager} built from {@link #stdout()}
         * keeps painting to the real stdout while {@code JkManager.captureOutput()} has {@link
         * System#out} redirected into its own line sink. Resolving per write would feed the
         * region's own paint back through the sink (writeAbove &rarr; paint &rarr; sink &rarr; …).
         */
        LiveSystemStream(boolean err) {
            this.target = err ? System.err : System.out;
            this.err = err;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (!SCRIPT_MODE.get() || err) {
                LAST_WRITE_ON_ERR.set(err);
                if (ENVELOPE_STARTED.compareAndSet(false, true) && !isLineBreak(b, off, len)) {
                    target.println();
                }
            }
            target.write(b, off, len);
        }

        @Override
        public void flush() {
            target.flush();
        }
    }
}
