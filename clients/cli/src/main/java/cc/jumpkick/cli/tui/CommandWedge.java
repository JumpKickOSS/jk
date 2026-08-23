// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import java.io.PrintStream;
import java.util.List;

/**
 * Human-facing settled command result chrome.
 *
 * <p>Preferred name for what historically lived as {@code BuildPlanWedge} (now {@link JkWedge}): every interactive
 * command should settle with a wedge (or a tree / table / wizard substitute). Agents should use
 * {@code --json} / wire / BSP — not scrape these lines.
 *
 * <h2>Output modes</h2>
 *
 * <table>
 * <tr><th>Mode</th><th>Trigger</th><th>Chrome</th></tr>
 * <tr><td>nerd</td><td>ansi + {@link GlobalConfig#nerdFont}</td><td>PUA caps + Unicode glyphs</td></tr>
 * <tr><td>ansi</td><td>ansi, no PUA axis granted</td><td>colored chip + trailing bg space, Unicode glyphs, no PUA</td></tr>
 * <tr><td>plain</td><td>NO_COLOR / --no-ansi</td><td>{@code +}/{@code !}/{@code *} prefixes</td></tr>
 * <tr><td>verbose</td><td>{@code -v}</td><td>same wedge + extra post-wedge detail (caller)</td></tr>
 * <tr><td>json</td><td>{@code --output json}</td><td>no wedge — structured events only</td></tr>
 * </table>
 *
 * <h2>Blank-line envelope</h2>
 *
 * <p>Human commands print exactly <strong>one blank line before</strong> the first chrome line of
 * the invocation and <strong>one blank line after</strong> the last chrome when the command
 * exits. {@link cc.jumpkick.cli.CliOutput} owns that lifecycle — it opens the envelope on the first
 * write and dispatch closes it after {@code run}. This class formats wedges and, for chrome whose
 * first write goes to a stream {@code CliOutput} does not own, opens the envelope on that stream.
 * Do <strong>not</strong> add a trailing blank in settles. A command whose stdout is consumed by a
 * program declares {@code CliCommand.scriptMode(Invocation)} instead of printing wedges to it.
 *
 * <p>Colors: blue/work chip for {@link #working}, green for {@link #ok}, red for {@link #fail}.
 * Subprocess streams go <em>before</em> the wedge; engine detail after (or details.jsonl).
 *
 * <p>For indeterminate work that should keep CommandWedge chrome (e.g. {@code jk status}
 * collecting metrics), use {@link #analyzing} — a live blue chip with a pulsing spinner icon —
 * then print a settled line ({@link #chip}, {@link #ok}, …) after it closes.
 */
public final class CommandWedge {

    private CommandWedge() {}

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
        return JkWedge.failureLine(command, GlobalConfig.nerdFont(), tail);
    }

    /** Explicit capability pair for tests / custom rendering. */
    public static String ok(String command, String message, NerdFontCaps caps) {
        return JkWedge.ok(command, message).renderLine(RenderContext.current().withCaps(caps));
    }

    public static String fail(String command, String message, NerdFontCaps caps) {
        return JkWedge.fail(command, message).renderLine(RenderContext.current().withCaps(caps));
    }

    /**
     * Leading blank of the human chrome envelope on stdout — at most once per command. Safe to call
     * from every chrome entry (spinner, bar, settle). The matching trailing blank is
     * {@link cc.jumpkick.cli.CliOutput#closeEnvelope()}, from dispatch.
     */
    public static void envelopeStart() {
        CliOutput.ensureLeadingBlank();
    }

    /**
     * Like {@link #envelopeStart()} but writes the blank on {@code out}. Needed when the first
     * chrome of a command reaches the terminal through a stream {@code CliOutput} does not own — a
     * {@link JkManager} sink, a {@link Spinner} writer, a test capture — which would otherwise print
     * before the envelope opened. {@code out} must be stdout-side; stderr chrome calls
     * {@link #envelopeStartErr()}.
     */
    public static void envelopeStart(PrintStream out) {
        CliOutput.ensureLeadingBlank(out);
    }

    /**
     * Mark the envelope as already opened without printing. Use when chrome that owns its own
     * leading blank (wizard header, terminal writer) ran first so later {@link #printOk} /
     * {@link #envelopeStart} calls do not insert a second blank.
     */
    public static void markEnvelopeStarted() {
        CliOutput.markEnvelopeStarted();
    }

    /**
     * Print a success settle with leading blank only: blank (if first chrome), then {@link #ok}.
     * Prefer this for one-shot commands over raw {@link CliOutput#out} of a check glyph.
     */
    public static void printOk(String command, String message) {
        CliOutput.out(ok(command, message));
    }

    /**
     * Print a failure settle with leading blank only on stderr when this is first chrome; then
     * {@link #fail}.
     */
    public static void printFail(String command, String message) {
        CliOutput.err(fail(command, message));
    }

    /**
     * Print a failure settle with "Failed to …" phrasing and a leading stderr blank when first.
     */
    public static void printFailedTo(String command, String tail) {
        CliOutput.err(failedTo(command, tail));
    }

    /**
     * Working / play chip on stderr (exec handoff, watch loop) with leading blank when first chrome.
     */
    public static void printWorking(String command, String message) {
        CliOutput.err(working(command, message));
    }

    /** Generic glyph chip on stdout with envelope. */
    public static void printChip(String glyph, String command, String message) {
        CliOutput.out(chip(glyph, command, message));
    }

    /** Generic glyph chip on stderr with envelope. */
    public static void printChipErr(String glyph, String command, String message) {
        CliOutput.err(chip(glyph, command, message));
    }

    /**
     * Settled ok wedge with IdeChrome-style check children under a {@link Tree} (no live animation).
     * Empty {@code details} prints only the wedge line.
     */
    public static void printOkTree(String command, String message, List<RichText> details) {
        printOkTree(command, RichText.plain(message == null ? "" : message), details);
    }

    /** Like {@link #printOkTree(String, String, java.util.List)} with a rich message tail. */
    public static void printOkTree(String command, RichText message, List<RichText> details) {
        JkWedge title = JkWedge.ok(command, message == null ? RichText.empty() : message);
        Tree tree = new Tree(title).gap(Tree.Gap.NONE);
        if (details != null && !details.isEmpty()) {
            RichText check = RichText.parse("[success]" + Glyphs.check() + "[/] ");
            for (RichText detail : details) {
                if (detail != null && !detail.isEmpty()) {
                    tree.child(Tree.node(check.plus(detail)));
                }
            }
        }
        tree.print();
    }

    /**
     * Print a pre-rendered wedge line (e.g. {@link JkWedge#chipLine} / cancelled job) on stdout with
     * the leading blank when this is first chrome.
     */
    public static void printLine(String wedgeLine) {
        CliOutput.out(wedgeLine);
    }

    /**
     * Print a pre-rendered wedge line on stderr with the leading blank when this is first chrome.
     */
    public static void printErrLine(String wedgeLine) {
        CliOutput.err(wedgeLine);
    }

    /**
     * Leading blank on stderr once per command (failure / working chrome path). Public so multi-line
     * error chrome (warn line + fail wedge) can open the envelope before the first line.
     */
    public static void envelopeStartErr() {
        CliOutput.ensureLeadingBlankErr();
    }
}
