// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import java.io.PrintStream;

/**
 * Human-facing settled command result chrome.
 *
 * <p>Preferred name for what historically lived as {@link PipelineWedge}: every interactive
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
 * <p>Human wedge-bearing commands print exactly one blank line before the first chrome line and
 * one blank line after the last chrome line. Use {@link #printOk}/{@link #printFail} for one-shot
 * settles, or {@link #envelopeStart}/{@link #envelopeEnd} around multi-line chrome. Pipeline
 * {@link CommandManager} owns the envelope for live progress + settle. Script-mode commands
 * (paths, tokens, shell hooks) must not use the envelope.
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
        return PipelineWedge.chipLine(Glyphs.CHECK, command, GlobalConfig.nerdfont(), message);
    }

    /** Red cross chip + message (done with error). Prefer this over {@code "jk cmd: …"} prefixes. */
    public static String fail(String command, String message) {
        return PipelineWedge.failureLineCustom(command, GlobalConfig.nerdfont(), message);
    }

    /** Blue / neutral working chip (play glyph) + message. */
    public static String working(String command, String message) {
        return PipelineWedge.chipLine(Glyphs.PLAY, command, GlobalConfig.nerdfont(), message);
    }

    /**
     * Live working CommandWedge: blue chip with a pulsing spinner icon and {@code message} after the
     * cap (e.g. {@code ● Status  Analyzing status...}). Silent under {@code --no-progress}.
     * Clears the line on close so the caller can settle with {@link #chip} / {@link #ok} in place.
     */
    public static Spinner analyzing(PrintStream out, String command, String message) {
        return Spinner.showWedge(out, command, message);
    }

    /**
     * Blue menu chip used as the left half of a box-table title ({@link BoxTable#titleBar}): {@code
     * ≡ Title} on the pipeline-blue chip. Prefer {@link BoxTable#titleBar} for full table chrome.
     */
    public static String menu(String title) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return "= " + (title == null ? "" : title);
        }
        return PipelineWedge.chip(Glyphs.MENU, title == null ? "" : title, t.pipelineChip())
                + PipelineWedge.cap(t.planBadgeColor(), GlobalConfig.nerdfont());
    }

    /** Generic chip with caller-chosen glyph. */
    public static String chip(String glyph, String command, String message) {
        return PipelineWedge.chipLine(glyph, command, GlobalConfig.nerdfont(), message);
    }

    /** Settled failure with "Failed to &lt;command&gt;" phrasing. */
    public static String failedTo(String command, String tail) {
        return PipelineWedge.failureLine(command, GlobalConfig.nerdfont(), tail);
    }

    /** Explicit nerdfont flag for tests / custom rendering. */
    public static String ok(String command, String message, boolean nerdfont) {
        return PipelineWedge.chipLine(Glyphs.CHECK, command, nerdfont, message);
    }

    public static String fail(String command, String message, boolean nerdfont) {
        return PipelineWedge.failureLineCustom(command, nerdfont, message);
    }

    /**
     * Leading blank of the human chrome envelope. Call once before the first wedge / table / tree
     * line. No-op under machine JSON modes is the caller's responsibility.
     */
    public static void envelopeStart() {
        CliOutput.out();
    }

    /** Trailing blank of the human chrome envelope. Call once after the last chrome line. */
    public static void envelopeEnd() {
        CliOutput.out();
    }

    /**
     * Print a success settle with the blank-line envelope on stdout: blank, {@link #ok}, blank.
     * Prefer this for one-shot commands over raw {@link CliOutput#out} of a check glyph.
     */
    public static void printOk(String command, String message) {
        envelopeStart();
        CliOutput.out(ok(command, message));
        envelopeEnd();
    }

    /**
     * Print a failure settle with the blank-line envelope on stderr: blank, {@link #fail}, blank.
     */
    public static void printFail(String command, String message) {
        CliOutput.err();
        CliOutput.err(fail(command, message));
        CliOutput.err();
    }

    /**
     * Print a failure settle with "Failed to …" phrasing and the stderr blank-line envelope.
     */
    public static void printFailedTo(String command, String tail) {
        CliOutput.err();
        CliOutput.err(failedTo(command, tail));
        CliOutput.err();
    }
}
