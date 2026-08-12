// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.config.GlobalConfig;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Human-facing settled command result chrome.
 *
 * <p>Preferred name for what historically lived as {@link BuildPlanWedge}: every interactive
 * command should settle with a wedge (or a tree / table / wizard substitute). Agents should use
 * {@code --json} / wire / BSP — not scrape these lines.
 *
 * <h2>Output modes</h2>
 *
 * <table>
 * <tr><th>Mode</th><th>Trigger</th><th>Chrome</th></tr>
 * <tr><td>nerd</td><td>ansi + {@link GlobalConfig#nerdfont}</td><td>PUA caps + Unicode glyphs</td></tr>
 * <tr><td>ansi</td><td>ansi, nerdfont false</td><td>colored chip + trailing bg space, Unicode glyphs, no PUA</td></tr>
 * <tr><td>plain</td><td>NO_COLOR / --no-ansi</td><td>{@code +}/{@code !}/{@code *} prefixes</td></tr>
 * <tr><td>verbose</td><td>{@code -v}</td><td>same wedge + extra post-wedge detail (caller)</td></tr>
 * <tr><td>json</td><td>{@code --output json}</td><td>no wedge — structured events only</td></tr>
 * </table>
 *
 * <h2>Blank-line envelope (JK-1373)</h2>
 *
 * <p>Human wedge-bearing commands print exactly <strong>one blank line before</strong> the first
 * chrome line of the invocation (prep spinner, live bar, or settle chip — whichever comes first).
 * {@link #envelopeStart()} is idempotent for the life of a command ({@link #resetEnvelope} at
 * dispatch). Do <strong>not</strong> add a trailing blank after the settle line. Spinners,
 * {@link JkManager}, and {@link #printOk}/{@link #printFail} all go through
 * {@link #envelopeStart}. Script-mode commands (paths, tokens, shell hooks) must not use the
 * envelope.
 *
 * <p>Colors: blue/work chip for {@link #working}, green for {@link #ok}, red for {@link #fail}.
 * Subprocess streams go <em>before</em> the wedge; engine detail after (or details.jsonl).
 *
 * <p>For indeterminate work that should keep CommandWedge chrome (e.g. {@code jk status}
 * collecting metrics), use {@link #analyzing} — a live blue chip with a pulsing spinner icon —
 * then print a settled line ({@link #chip}, {@link #ok}, …) after it closes.
 */
public final class CommandWedge {

    /**
     * Once true, {@link #envelopeStart()} is a no-op until {@link #resetEnvelope()}. Reset at the
     * start of each leaf command so prep lock wedges and main settles share one leading blank.
     */
    private static final AtomicBoolean ENVELOPE_STARTED = new AtomicBoolean(false);

    private CommandWedge() {}

    /** Clear the per-command envelope flag — call from command dispatch before {@code run}. */
    public static void resetEnvelope() {
        ENVELOPE_STARTED.set(false);
    }

    /** True after the leading blank has been printed for this command. */
    public static boolean envelopeStarted() {
        return ENVELOPE_STARTED.get();
    }

    /** Green check chip + message (done successfully). */
    public static String ok(String command, String message) {
        return JkWedge.ok(command, message).renderLine(RenderContext.current());
    }

    /** Red cross chip + message (done with error). Prefer this over {@code "jk cmd: …"} prefixes. */
    public static String fail(String command, String message) {
        return JkWedge.fail(command, message).renderLine(RenderContext.current());
    }

    /** Blue / neutral working chip (play glyph) + message. */
    public static String working(String command, String message) {
        return JkWedge.work(command, message).renderLine(RenderContext.current());
    }

    /**
     * Live working CommandWedge: blue chip with a pulsing spinner icon and {@code message} after the
     * cap (e.g. {@code ● Status  Analyzing status...}). Silent under {@code --no-progress}.
     * Clears the line on close so the caller can settle with {@link #chip} / {@link #ok} in place.
     */
    public static Spinner analyzing(PrintStream out, String command, String message) {
        // showWedge calls envelopeStart(out) — first chrome may be prep lock, not the final settle
        return Spinner.showWedge(out, command, message);
    }

    /**
     * Blue menu chip used as the left half of a box-table title: {@code
     * ≡ Title} on the plan-blue chip. Prefer {@link Table} for full table chrome.
     */
    public static String menu(String title) {
        return JkWedge.menu(title).renderLine(RenderContext.current());
    }

    /** Generic chip with caller-chosen glyph. */
    public static String chip(String glyph, String command, String message) {
        return new JkWedge(Icon.fromGlyph(glyph), command, RichText.ansi(message == null ? "" : message))
                .renderLine(RenderContext.current());
    }

    /** Settled failure with "Failed to &lt;command&gt;" phrasing. */
    public static String failedTo(String command, String tail) {
        return JkWedge.failureLine(command, GlobalConfig.nerdfont(), tail);
    }

    /** Explicit nerdfont flag for tests / custom rendering. */
    public static String ok(String command, String message, boolean nerdfont) {
        return JkWedge.ok(command, message).renderLine(RenderContext.current().withNerd(nerdfont));
    }

    public static String fail(String command, String message, boolean nerdfont) {
        return JkWedge.fail(command, message).renderLine(RenderContext.current().withNerd(nerdfont));
    }

    /**
     * Leading blank of the human chrome envelope on stdout — at most once per command (see
     * {@link #resetEnvelope}). Safe to call from every chrome entry (spinner, bar, settle). There
     * is no matching trailing blank.
     */
    public static void envelopeStart() {
        envelopeStart(CliOutput.stdout());
    }

    /**
     * Like {@link #envelopeStart()} but writes the blank on {@code out} (e.g. a test capture stream
     * or {@link JkManager}'s sink).
     */
    public static void envelopeStart(PrintStream out) {
        if (ENVELOPE_STARTED.compareAndSet(false, true)) {
            out.println();
        }
    }

    /**
     * Print a success settle with leading blank only: blank (if first chrome), then {@link #ok}.
     * Prefer this for one-shot commands over raw {@link CliOutput#out} of a check glyph.
     */
    public static void printOk(String command, String message) {
        envelopeStart();
        CliOutput.out(ok(command, message));
    }

    /**
     * Print a failure settle with leading blank only on stderr when this is first chrome; then
     * {@link #fail}.
     */
    public static void printFail(String command, String message) {
        if (ENVELOPE_STARTED.compareAndSet(false, true)) {
            CliOutput.err();
        }
        CliOutput.err(fail(command, message));
    }

    /**
     * Print a failure settle with "Failed to …" phrasing and a leading stderr blank when first.
     */
    public static void printFailedTo(String command, String tail) {
        if (ENVELOPE_STARTED.compareAndSet(false, true)) {
            CliOutput.err();
        }
        CliOutput.err(failedTo(command, tail));
    }
}
