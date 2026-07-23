// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.config.GlobalConfig;

/**
 * Human-facing settled command result chrome (JK-1076 / JK-1077).
 *
 * <p>Preferred name for what historically lived as {@link PipelineWedge}: every interactive
 * command should settle with a wedge (or a tree / table / wizard substitute). Agents should use
 * {@code --json} / wire / BSP — not scrape these lines.
 *
 * <h2>Output modes</h2>
 *
 * <table>
 *   <tr><th>Mode</th><th>Trigger</th><th>Chrome</th></tr>
 *   <tr><td>nerd</td><td>ansi + {@link GlobalConfig#nerdfont()}</td><td>PUA caps + Unicode glyphs</td></tr>
 *   <tr><td>ansi</td><td>ansi, nerdfont false</td><td>colored chip + trailing bg space, Unicode glyphs, no PUA</td></tr>
 *   <tr><td>plain</td><td>NO_COLOR / --no-ansi</td><td>{@code +}/{@code !}/{@code *} prefixes</td></tr>
 *   <tr><td>verbose</td><td>{@code -v}</td><td>same wedge + extra post-wedge detail (caller)</td></tr>
 *   <tr><td>json</td><td>{@code --output json}</td><td>no wedge — structured events only</td></tr>
 * </table>
 *
 * <p>Colors: blue/work chip for {@link #working}, green for {@link #ok}, red for {@link #fail}.
 * Subprocess streams go <em>before</em> the wedge; engine detail after (or details.json).
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
}
