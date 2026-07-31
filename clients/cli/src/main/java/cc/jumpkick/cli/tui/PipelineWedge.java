// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import java.util.Locale;
import org.jline.utils.AttributedStyle;

/**
 * Shared chrome for the build pipeline line and its settled result lines, so the live header ({@link
 * CommandManager#pipelineHeader}) and the buffered plain-scheduler println render identically.
 *
 * <p>The line is a powerline chip: {@code ✓ Build } painted on a colored chip, closed by a cap:
 * with a Nerd Font, U+E0B0 whose <em>foreground</em> is the chip color (solid body continues the
 * chip) and whose <em>background</em> is left unset (tapers into what follows); without a Nerd Font,
 * a trailing space painted with the chip color as <em>background</em> (same width idea, no PUA).
 */
public final class PipelineWedge {

    private PipelineWedge() {}

    /**
     * {@code " {glyph} {name} "} painted on {@code chip}. A leading + trailing space pad the pill.
     */
    static String chip(String glyph, String name, AttributedStyle chip) {
        String text = " " + glyph + (name.isEmpty() ? "" : " " + name) + " ";
        return Theme.colorize(text, chip);
    }

    /**
     * Cap closing a chip. Nerd Font: U+E0B0 with foreground = {@code chipColor} (continues the chip
     * body; background unset so it tapers). Plain ANSI: a single space with background = {@code
     * chipColor} (same visual end of the pill, no powerline glyph).
     */
    static String cap(Rgb chipColor, boolean nerdfont) {
        Theme t = Theme.active();
        if (nerdfont) {
            return Theme.colorize(Glyphs.SEGMENT_END_NERD, t.bright(chipColor));
        }
        return Theme.colorize(" ", t.withBackground(AttributedStyle.DEFAULT, chipColor));
    }

    /**
     * A generic settled chip line: {@code ✓ Clean ▶ <message>}. The {@code glyph} + {@code command} form
     * the chip (closed by the powerline cap); {@code message} is caller-styled and follows the cap.
     * For commands whose result reads as its own sentence (e.g. {@code jk clean}'s "Removed N files")
     * rather than the "{pipeline} successful" phrasing of {@link #successLine}.
     *
     * <p>No-ANSI: ASCII-only prefixes — {@code "+"} for {@link Glyphs#CHECK}, {@code "!"} for {@link
     * Glyphs#CROSS}, {@code "*"} for anything else — with a {@code ": "} separator and no color.
     */
    public static String chipLine(String glyph, String command, boolean nerdfont, String message) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            String prefix = Glyphs.CHECK.equals(glyph) ? "+" : Glyphs.CROSS.equals(glyph) ? "!" : "*";
            return prefix + " " + command + ": " + message;
        }
        // ✓ (done) and ▶ (running) read as positive → green chip; everything else (■ stop, spinner
        // frames, …) uses the neutral blue chip.
        boolean green = Glyphs.CHECK.equals(glyph) || Glyphs.PLAY.equals(glyph);
        var chipStyle = green ? t.pipelineSuccessChip() : t.pipelineChip();
        var capColor = green ? t.pipelineChipColor() : t.planBadgeColor();
        // Chip + cap (PUA arrow or plain bg-colored space) then message.
        return chip(glyph, command, chipStyle) + cap(capColor, nerdfont) + " " + message;
    }

    /** {@code group:name} with the group cyan and the name bright-cyan — for failure tails. */
    public static String coord(String coord) {
        Theme t = Theme.active();
        int i = coord.indexOf(':');
        return i < 0
                ? Theme.colorize(coord, t.coordGroup())
                : Theme.colorize(coord.substring(0, i), t.coordGroup())
                        + ":"
                        + Theme.colorize(coord.substring(i + 1), t.coordName());
    }

    /**
     * Settled failure: {@code ✘ Build ▶ Failed to build <tail>} — "Failed" in red, then "to
     * &lt;pipeline&gt;" (the pipeline name lower-cased: build, test, …) in the default color; tail pre-styled
     * by the caller.
     *
     * <p>No-ANSI: {@code "! <command> Failed: <tail>"} — ASCII only, no color.
     */
    public static String failureLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return "! " + name + " Failed: " + tail;
        }
        String command =
                Theme.colorize("Failed", t.error()) + (name.isEmpty() ? "" : " to " + name.toLowerCase(Locale.ROOT));
        return chip(Glyphs.CROSS, name, t.pipelineFailureChip())
                + cap(t.pipelineFailColor(), nerdfont)
                + " "
                + command
                + " "
                + tail;
    }

    /**
     * Settled failure with a caller-composed sentence, skipping the "Failed to &lt;pipeline&gt;"
     * derivation {@link #failureLine} does — for a result that settles by chip but whose message
     * doesn't read as "Failed to {@code <command>}" (e.g. {@code jk run}: "Failed to run acme:api. No
     * valid main method was specified or detected"). {@code sentence} is fully pre-styled by the
     * caller, including its own leading "Failed" if wanted.
     *
     * <p>No-ANSI: {@code "! <name>: <sentence>"} — ASCII only, no color; no separate "Failed:" since
     * the caller-composed sentence already reads as one (unlike {@link #failureLine}'s {@code tail}).
     */
    public static String failureLineCustom(String name, boolean nerdfont, String sentence) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return "! " + name + ": " + sentence;
        }
        return chip(Glyphs.CROSS, name, t.pipelineFailureChip()) + cap(t.pipelineFailColor(), nerdfont) + " "
                + sentence;
    }

    /**
     * Settled cancel. Same red chip as a failure. The chip already names the pipeline,
     * so the body does not repeat it
     *
     * <ul>
     * <li>Ctrl-C ({@code byUser}): {@code ✘ Build job was cancelled by user took 1.6s}
     * <li>Remote ({@code jk cancel} / web): {@code ✘ Build job was cancelled took 1.6s}
     * </ul>
     *
     * {@code cancelled} is warning yellow; {@code tookTail} is caller-styled (e.g.
     * {@link cc.jumpkick.cli.run.ConsoleSpec#took}) or empty.
     *
     * <p>No-ANSI: {@code "! <name> job was cancelled[ by user][ took …]"}
     */
    public static String cancelledJobLine(String name, boolean nerdfont, boolean byUser, String tookTail) {
        Theme t = Theme.active();
        String took = tookTail == null || tookTail.isBlank() ? "" : " " + tookTail;
        if (!t.isAnsi()) {
            return "! " + name + " job was cancelled" + (byUser ? " by user" : "") + took;
        }
        String body = "job was " + Theme.colorize("cancelled", t.warning()) + (byUser ? " by user" : "") + took;
        return chip(Glyphs.CROSS, name, t.pipelineFailureChip()) + cap(t.pipelineFailColor(), nerdfont) + " " + body;
    }

    /** Remote cancel — no "by user". */
    public static String cancelledJobLine(String name, boolean nerdfont, String tookTail) {
        return cancelledJobLine(name, nerdfont, false, tookTail);
    }
}
