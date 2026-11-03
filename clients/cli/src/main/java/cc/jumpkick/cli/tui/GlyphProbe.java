// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.TerminalSession;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the terminal about itself, for {@code jk self setup-terminal} only: never on a build's path.
 * Each question is time-bounded and a terminal that ignores it simply yields nothing.
 *
 * <p>Neither answer proves a glyph is drawn. The reported font suggests what the user has selected;
 * the glyph width only exposes terminals that draw a missing glyph as a double-wide box. The
 * user's own answer is what settles the result.
 */
public final class GlyphProbe {

    /** What the terminal volunteered. */
    public record Findings(Optional<String> reportedFont, OptionalInt glyphCells) {
        public static final Findings NOTHING = new Findings(Optional.empty(), OptionalInt.empty());
    }

    private static final Duration FIRST = Duration.ofMillis(250);
    private static final Duration IDLE = Duration.ofMillis(40);

    /** The wedge codepoint used for the width probe. */
    private static final String PROBE_GLYPH = "";

    private static final Pattern OSC_FONT = Pattern.compile("\u001b\\]50;([^\u0007\u001b]*)");
    private static final Pattern CURSOR_REPORT = Pattern.compile("\u001b\\[(\\d+);(\\d+)R");

    private GlyphProbe() {}

    /** Probe {@code tty}; returns {@link Findings#NOTHING} on any failure. */
    public static Findings run(TerminalSession tty) {
        try (var mode = tty.enter(InputMode.PROMPT)) {
            tty.drain(Duration.ofMillis(10));
            PrintWriter out = tty.ttyOut();
            // xterm's font query, followed by a status request every terminal answers: the status
            // reply marks the end of whatever the font query got.
            out.print("\u001b]50;?\u0007\u001b[5n");
            out.flush();
            Optional<String> font = parseFont(tty.readReply(FIRST, IDLE));

            OptionalInt before = askColumn(tty, out, "\r");
            OptionalInt after = askColumn(tty, out, "\r" + PROBE_GLYPH);
            out.print("\r\u001b[2K");
            out.flush();
            OptionalInt cells = before.isPresent() && after.isPresent()
                    ? OptionalInt.of(after.getAsInt() - before.getAsInt())
                    : OptionalInt.empty();
            return new Findings(font, cells);
        } catch (RuntimeException e) {
            return Findings.NOTHING;
        }
    }

    private static OptionalInt askColumn(TerminalSession tty, PrintWriter out, String prefix) {
        out.print(prefix + "\u001b[6n");
        out.flush();
        return parseCursorColumn(tty.readReply(FIRST, IDLE));
    }

    /** The font named by an {@code OSC 50} reply, if the reply carries a usable one. */
    static Optional<String> parseFont(String reply) {
        Matcher m = OSC_FONT.matcher(reply);
        if (!m.find()) return Optional.empty();
        String font = m.group(1).strip();
        return font.isEmpty() || font.equals("?") ? Optional.empty() : Optional.of(font);
    }

    /** The column from a cursor position report {@code ESC [ row ; col R}. */
    static OptionalInt parseCursorColumn(String reply) {
        Matcher m = CURSOR_REPORT.matcher(reply);
        if (!m.find()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
