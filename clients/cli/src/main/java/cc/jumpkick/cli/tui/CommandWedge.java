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
 * <p>Modes: nerd (PUA caps) · ansi Unicode · plain ASCII — selected via theme + {@link
 * GlobalConfig#nerdfont()}.
 */
public final class CommandWedge {

    private CommandWedge() {}

    /** Green check chip + message (done successfully). */
    public static String ok(String command, String message) {
        return PipelineWedge.chipLine(Glyphs.CHECK, command, GlobalConfig.nerdfont(), message);
    }

    /** Red cross chip + message (done with error). */
    public static String fail(String command, String message) {
        return PipelineWedge.failureLineCustom(command, GlobalConfig.nerdfont(), message);
    }

    /** Blue / neutral working chip (e.g. play glyph) + message. */
    public static String working(String command, String message) {
        return PipelineWedge.chipLine(Glyphs.PLAY, command, GlobalConfig.nerdfont(), message);
    }

    /** Generic chip with caller-chosen glyph. */
    public static String chip(String glyph, String command, String message) {
        return PipelineWedge.chipLine(glyph, command, GlobalConfig.nerdfont(), message);
    }

    /** Delegates to {@link PipelineWedge#failureLine}. */
    public static String failedTo(String command, String tail) {
        return PipelineWedge.failureLine(command, GlobalConfig.nerdfont(), tail);
    }
}
