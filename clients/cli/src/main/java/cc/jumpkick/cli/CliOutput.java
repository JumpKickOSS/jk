// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.tui.PlainAscii;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * <p>Under {@code --no-ansi}, string writes run through {@link PlainAscii} so ellipsis, bullets,
 * and pulse circles in free-form messages become ASCII without each command hand-substituting.
 *
 * <h2>Blank-line envelope</h2>
 *
 * <p>Human commands print exactly one blank line before the first chrome of the invocation and
 * one blank line after the last chrome when the command exits. This class owns that rule: the
 * first {@link #out}/{@link #err}/{@link #stdout}/{@link #stderr} write inserts the leading blank;
 * dispatch calls {@link #closeEnvelope} after {@code run}. Settles themselves do not print the
 * trailing blank — {@code jk run} must be able to hand off to {@code inheritIO} with no gap.
 *
 * <p>Machine-consumed stdout must call {@link #skipEnvelope} before printing (paths, tokens, eval
 * scripts, JSON objects). Dispatch already skips for {@code --output json}/{@code jsonl}.
 */
public final class CliOutput {

    /**
     * Once true, the leading blank has been printed for this command. Reset at dispatch so prep
     * spinners and settle chips share one envelope.
     */
    private static final AtomicBoolean ENVELOPE_STARTED = new AtomicBoolean(false);

    /** When true, the first write does not insert a leading blank (script / JSON stdout). */
    private static final AtomicBoolean SKIP_ENVELOPE = new AtomicBoolean(false);

    /** Once true, {@link #closeEnvelope} has printed the trailing blank (or skipped). */
    private static final AtomicBoolean ENVELOPE_CLOSED = new AtomicBoolean(false);

    /**
     * True when the leading blank landed on stderr (failure / working chrome). Trailing blank
     * follows that stream so a fail-only command does not leak a newline onto stdout.
     */
    private static final AtomicBoolean ENVELOPE_ON_ERR = new AtomicBoolean(false);

    private CliOutput() {}

    /** Clear envelope state — call from command dispatch before {@code run}. */
    public static void resetEnvelope() {
        ENVELOPE_STARTED.set(false);
        SKIP_ENVELOPE.set(false);
        ENVELOPE_CLOSED.set(false);
        ENVELOPE_ON_ERR.set(false);
    }

    /**
     * Do not insert a leading or trailing blank on this command. Use immediately before
     * machine-consumed stdout (eval scripts, paths, tokens, raw JSON) so command substitution and
     * {@code eval "$(jk …)"} stay parseable.
     */
    public static void skipEnvelope() {
        SKIP_ENVELOPE.set(true);
    }

    /** True after the leading blank has been printed for this command. */
    public static boolean envelopeStarted() {
        return ENVELOPE_STARTED.get();
    }

    /**
     * Mark the envelope as already opened without printing. Use when chrome that owns its own
     * leading blank (wizard header, terminal writer) ran first so later writes do not insert a
     * second blank.
     */
    public static void markEnvelopeStarted() {
        ENVELOPE_STARTED.set(true);
    }

    /**
     * Leading blank on stdout — at most once per command. Safe to call from every chrome entry
     * (spinner, bar, settle). No-op after {@link #skipEnvelope}.
     */
    public static void ensureLeadingBlank() {
        ensureLeadingBlank(System.out);
    }

    /** Leading blank on stderr once per command (failure / working chrome). */
    public static void ensureLeadingBlankErr() {
        ensureLeadingBlank(System.err);
    }

    /**
     * Leading blank on {@code dest} (test capture stream, {@code JkManager} sink, or a live
     * terminal writer). Shares the per-command flag with {@link #out} / {@link #err}.
     */
    public static void ensureLeadingBlank(PrintStream dest) {
        if (dest == null || SKIP_ENVELOPE.get()) return;
        if (ENVELOPE_STARTED.compareAndSet(false, true)) {
            ENVELOPE_ON_ERR.set(dest == System.err);
            dest.println();
        }
    }

    /**
     * Trailing blank after the last chrome of a human command. Idempotent. No-op when the envelope
     * was skipped or never opened (no chrome). Dispatch calls this after {@code run} so individual
     * commands and settles do not.
     */
    public static void closeEnvelope() {
        if (SKIP_ENVELOPE.get() || !ENVELOPE_STARTED.get()) return;
        if (!ENVELOPE_CLOSED.compareAndSet(false, true)) return;
        PrintStream dest = ENVELOPE_ON_ERR.get() ? System.err : System.out;
        dest.println();
        dest.flush();
    }

    /** Record that the envelope opened on stdout or stderr. No-op after the first chrome. */
    private static void markStarted(boolean err) {
        if (SKIP_ENVELOPE.get()) return;
        if (ENVELOPE_STARTED.compareAndSet(false, true)) {
            ENVELOPE_ON_ERR.set(err);
        }
    }

    /** Print a line to stdout (result output). */
    public static void out(String line) {
        if (line == null || line.isEmpty()) {
            out();
            return;
        }
        ensureLeadingBlank(System.out);
        System.out.println(PlainAscii.apply(line));
    }

    /** Print a blank line to stdout. The first blank of a command <em>is</em> the envelope. */
    public static void out() {
        markStarted(false);
        System.out.println();
    }

    /** Print to stdout with no trailing newline. */
    public static void outRaw(String s) {
        ensureLeadingBlank(System.out);
        System.out.print(PlainAscii.apply(s));
    }

    /** Print a line to stderr (diagnostics, errors, progress). */
    public static void err(String line) {
        if (line == null || line.isEmpty()) {
            err();
            return;
        }
        ensureLeadingBlank(System.err);
        System.err.println(PlainAscii.apply(line));
    }

    /** Print a blank line to stderr. The first blank of a command <em>is</em> the envelope. */
    public static void err() {
        markStarted(true);
        System.err.println();
    }

    /** Print to stderr with no trailing newline. */
    public static void errRaw(String s) {
        ensureLeadingBlank(System.err);
        System.err.print(PlainAscii.apply(s));
    }

    /**
     * Stdout for APIs that need a {@link PrintStream} (JkManager, Spinner, renderers). Under
     * plain mode the stream rewrites Unicode chrome via {@link PlainAscii#wrap}. The first write
     * opens the envelope.
     */
    public static PrintStream stdout() {
        return PlainAscii.wrap(new EnvelopeStream(false));
    }

    /**
     * Stderr for APIs that need a {@link PrintStream}. Under plain mode the stream rewrites
     * Unicode chrome via {@link PlainAscii#wrap}. The first write opens the envelope.
     */
    public static PrintStream stderr() {
        return PlainAscii.wrap(new EnvelopeStream(true));
    }

    /**
     * PrintStream over {@link LiveSystemStream}. Envelope insertion lives on the {@link
     * OutputStream} so every {@link PrintStream} path ({@code print}, {@code println}, {@code
     * printf}, {@code write}) shares one first-write hook.
     */
    private static final class EnvelopeStream extends PrintStream {
        EnvelopeStream(boolean err) {
            super(new LiveSystemStream(err), true, StandardCharsets.UTF_8);
        }
    }

    /** True when {@code buf[off,len]} is a non-empty run of {@code \n}/{@code \r} only. */
    static boolean onlyNewlines(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) return false;
        int end = Math.min(off + len, buf.length);
        for (int i = off; i < end; i++) {
            byte c = buf[i];
            if (c != '\n' && c != '\r') return false;
        }
        return true;
    }

    /**
     * Forwards to the current {@link System#out}/{@link System#err} without capturing the stream
     * at construction — tests swap those streams via {@code System.setOut}. The first write
     * inserts the leading blank unless the payload itself is that blank.
     */
    private static final class LiveSystemStream extends OutputStream {
        private final boolean err;

        LiveSystemStream(boolean err) {
            this.err = err;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            PrintStream raw = err ? System.err : System.out;
            if (!SKIP_ENVELOPE.get() && ENVELOPE_STARTED.compareAndSet(false, true)) {
                ENVELOPE_ON_ERR.set(err);
                if (!onlyNewlines(b, off, len)) {
                    raw.println();
                }
            }
            raw.write(b, off, len);
        }

        @Override
        public void flush() {
            (err ? System.err : System.out).flush();
        }
    }
}
