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
 * <h2>Chip shapes</h2>
 *
 * <ul>
 * <li><b>Nerd</b> — {@code " {glyph} {name} "} + powerline U+E0B0 cap (one trailing chip space
 * before the PUA arrow).
 * <li><b>ANSI, no nerd</b> — {@code " {glyph} {name}  "} with <strong>two</strong> trailing spaces
 * on the chip background (visual end of the pill without a PUA glyph).
 * <li><b>Plain ({@code --no-ansi})</b> — {@code " {ascii} {name} >"} then a space and the message
 * (the {@code >} replaces a color transition).
 * </ul>
 */
public final class PipelineWedge {

    private PipelineWedge() {}

    /**
     * Chip body + trailing pad on {@code chip} style. Trailing pad is one space when {@code
     * nerdfont} (powerline follows) or two spaces when not (pill end without PUA).
     */
    public static String chip(String glyph, String name, AttributedStyle chip, boolean nerdfont) {
        String body = " " + glyph + (name == null || name.isEmpty() ? "" : " " + name);
        String trail = nerdfont ? " " : "  ";
        return Theme.colorize(body + trail, chip);
    }

    /**
     * Cap closing a chip. Nerd Font: U+E0B0 with foreground = {@code chipColor}. ANSI without nerd:
     * empty — {@link #chip} already ends with two bg-colored spaces. Plain: empty (caller uses
     * {@link #plainWedge}).
     */
    public static String cap(Rgb chipColor, boolean nerdfont) {
        if (!nerdfont) return "";
        Theme t = Theme.active();
        return Theme.colorize(Glyphs.SEGMENT_END_NERD, t.bright(chipColor));
    }

    /**
     * Plain ({@code --no-ansi}) wedge: {@code " {ascii-icon} {command} >"} optionally followed by
     * {@code " " + message}.
     */
    public static String plainWedge(String asciiIcon, String command, String message) {
        String cmd = command == null ? "" : command;
        String icon = asciiIcon == null || asciiIcon.isEmpty() ? Glyphs.BULLET_PLAIN : asciiIcon;
        String head = " " + icon + " " + cmd + " >";
        if (message == null || message.isEmpty()) return head;
        return head + " " + message;
    }

    /** ASCII icon for a Unicode wedge glyph (CHECK/CROSS/PLAY/MENU/PULSE/…). */
    public static String plainIconFor(String glyph) {
        if (Glyphs.CHECK.equals(glyph)) return Glyphs.CHECK_PLAIN;
        if (Glyphs.CROSS.equals(glyph)) return Glyphs.CROSS_PLAIN;
        if (Glyphs.PLAY.equals(glyph)) return Glyphs.PLAY_PLAIN;
        if (Glyphs.MENU.equals(glyph)) return Glyphs.MENU_PLAIN;
        if (Glyphs.PULSE.equals(glyph)) return Glyphs.PULSE_PLAIN;
        if (Glyphs.STOP.equals(glyph)) return Glyphs.STOP_PLAIN;
        if (Glyphs.BANG.equals(glyph)) return Glyphs.BANG_PLAIN;
        return Glyphs.BULLET_PLAIN;
    }

    /**
     * A generic settled chip line: {@code ✓ Clean ▶ <message>}. The {@code glyph} + {@code command}
     * form the chip (closed by the powerline cap when nerd); {@code message} follows.
     *
     * <p>No-ANSI: {@code " + Clean > <message>"}.
     */
    public static String chipLine(String glyph, String command, boolean nerdfont, String message) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return plainWedge(plainIconFor(glyph), command, message);
        }
        // ✓ (done) and ▶ (running) read as positive → green chip; everything else uses blue.
        boolean green = Glyphs.CHECK.equals(glyph) || Glyphs.PLAY.equals(glyph);
        var chipStyle = green ? t.pipelineSuccessChip() : t.pipelineChip();
        var capColor = green ? t.pipelineChipColor() : t.planBadgeColor();
        String msg = message == null ? "" : message;
        return chip(glyph, command, chipStyle, nerdfont) + cap(capColor, nerdfont) + " " + msg;
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
     * Settled failure: {@code ✘ Build ▶ Failed to build <tail>}.
     *
     * <p>No-ANSI: {@code " ! Build > Failed to build <tail>"}.
     */
    public static String failureLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        String command =
                Theme.colorize("Failed", t.error()) + (name.isEmpty() ? "" : " to " + name.toLowerCase(Locale.ROOT));
        String body = command + " " + tail;
        if (!t.isAnsi()) {
            String plainBody = "Failed" + (name.isEmpty() ? "" : " to " + name.toLowerCase(Locale.ROOT)) + " " + tail;
            return plainWedge(Glyphs.CROSS_PLAIN, name, plainBody);
        }
        return chip(Glyphs.CROSS, name, t.pipelineFailureChip(), nerdfont)
                + cap(t.pipelineFailColor(), nerdfont)
                + " "
                + body;
    }

    /**
     * Settled failure with a caller-composed sentence.
     *
     * <p>No-ANSI: {@code " ! <name> > <sentence>"}.
     */
    public static String failureLineCustom(String name, boolean nerdfont, String sentence) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return plainWedge(Glyphs.CROSS_PLAIN, name, sentence);
        }
        return chip(Glyphs.CROSS, name, t.pipelineFailureChip(), nerdfont)
                + cap(t.pipelineFailColor(), nerdfont)
                + " "
                + sentence;
    }

    /**
     * Settled cancel. Same red chip as a failure.
     *
     * <p>No-ANSI: {@code " ! <name> > job was cancelled[ by user][ took …]"}.
     */
    public static String cancelledJobLine(String name, boolean nerdfont, boolean byUser, String tookTail) {
        Theme t = Theme.active();
        String took = tookTail == null || tookTail.isBlank() ? "" : " " + tookTail;
        if (!t.isAnsi()) {
            return plainWedge(
                    Glyphs.CROSS_PLAIN, name, "job was cancelled" + (byUser ? " by user" : "") + took);
        }
        String body = "job was " + Theme.colorize("cancelled", t.warning()) + (byUser ? " by user" : "") + took;
        return chip(Glyphs.CROSS, name, t.pipelineFailureChip(), nerdfont)
                + cap(t.pipelineFailColor(), nerdfont)
                + " "
                + body;
    }

    /** Remote cancel — no "by user". */
    public static String cancelledJobLine(String name, boolean nerdfont, String tookTail) {
        return cancelledJobLine(name, nerdfont, false, tookTail);
    }

    /**
     * Plan / menu style chip (always blue), e.g. {@code ≡ Build Plan}. Caller appends estimate text
     * after the cap.
     */
    public static String planChip(String glyph, String title, boolean nerdfont) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return plainWedge(plainIconFor(glyph), title, null);
        }
        return chip(glyph, title, t.planBadge(), nerdfont) + cap(t.planBadgeColor(), nerdfont);
    }
}
