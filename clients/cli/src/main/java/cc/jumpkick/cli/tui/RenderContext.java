// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import org.jline.utils.AttributedString;

/**
 * How to paint a widget: theme, the three human modes, terminal width, and an optional animator
 * frame. Widgets describe <em>what</em>; this is the only object that knows <em>how</em>.
 */
public record RenderContext(Theme theme, boolean ansi, boolean nerdfont, int width, int frame) {

    public static final int DEFAULT_WIDTH = 80;

    public enum Mode {
        NERD,
        ANSI,
        PLAIN
    }

    public RenderContext {
        if (theme == null) theme = Theme.active();
        if (width <= 0) width = DEFAULT_WIDTH;
        if (!ansi) nerdfont = false;
        if (frame < 0) frame = 0;
    }

    /**
     * Snapshot of the process-wide theme / nerd flag / terminal columns. Called on every
     * animation frame, so the width comes from the {@link TerminalSize} cache — never a fresh
     * probe (probing forks a subprocess).
     */
    public static RenderContext current() {
        Theme theme = Theme.active();
        boolean ansi = theme.isAnsi();
        boolean nerd = ansi && GlobalConfig.nerdfont();
        return new RenderContext(theme, ansi, nerd, TerminalSize.columns(), 0);
    }

    public Mode mode() {
        if (!ansi) return Mode.PLAIN;
        if (nerdfont) return Mode.NERD;
        return Mode.ANSI;
    }

    public RenderContext withFrame(int newFrame) {
        return new RenderContext(theme, ansi, nerdfont, width, newFrame);
    }

    public RenderContext withNerd(boolean nerd) {
        return new RenderContext(theme, ansi, nerd && ansi, width, frame);
    }

    public RenderContext withAnsi(boolean on) {
        return new RenderContext(theme, on, nerdfont && on, width, frame);
    }

    public RenderContext withWidth(int newWidth) {
        return new RenderContext(theme, ansi, nerdfont, newWidth, frame);
    }

    /**
     * OSC sequences (hyperlinks, taskbar progress): {@code ESC ] … (BEL | ESC \)}. The {@code \z}
     * alternative also strips a sequence truncated upstream (a tool line clipped mid-OSC) — its
     * payload must measure as zero columns, not as the URL's length (JK-1967).
     */
    private static final java.util.regex.Pattern OSC_SEQUENCE =
            java.util.regex.Pattern.compile("\\u001b\\][^\\u0007\\u001b]*(?:\\u0007|\\u001b\\\\|\\z)");

    /**
     * Index just past the escape sequence starting at {@code i} ({@code s.charAt(i)} is ESC):
     * CSI {@code ESC [ … final}, OSC {@code ESC ] … (BEL | ESC \)} — an unterminated OSC consumes
     * to end-of-string — else the two-char {@code ESC x} form. The one escape scanner shared by
     * the width/truncation helpers, so a private copy can never again learn only CSI and count a
     * hyperlink's URL as columns (JK-1967).
     */
    public static int skipEscape(String s, int i) {
        if (i + 1 >= s.length()) return s.length();
        char n = s.charAt(i + 1);
        if (n == '[') {
            int j = i + 2;
            while (j < s.length()) {
                char c = s.charAt(j++);
                if (c >= '@' && c <= '~') break;
            }
            return j;
        }
        if (n == ']') {
            int j = i + 2;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '\u0007') return j + 1;
                if (c == '\u001b') {
                    return (j + 1 < s.length() && s.charAt(j + 1) == '\\') ? j + 2 : j;
                }
                j++;
            }
            return s.length();
        }
        return i + 2;
    }

    /**
     * Strip CSI and OSC alike. JLine's {@code AttributedString.stripAnsi} leaves OSC bytes in
     * place, so an OSC-8 hyperlink would otherwise inflate measured width by its URL plus escape
     * bytes (JK-1887).
     */
    public static String stripAnsi(String s) {
        if (s == null) return "";
        String noOsc = s.indexOf('\u001b') < 0 ? s : OSC_SEQUENCE.matcher(s).replaceAll("");
        return AttributedString.stripAnsi(noOsc);
    }

    /**
     * Visible terminal columns: CSI/OSC stripped, then wcwidth (CJK = 2). Shared by every widget
     * that pads cells or fills a title bar.
     */
    public static int visibleWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        return new AttributedString(stripAnsi(s)).columnLength();
    }
}
