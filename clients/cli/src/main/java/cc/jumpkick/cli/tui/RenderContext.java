// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Size;
import cc.jumpkick.terminal.Width;

/**
 * How to paint a widget: theme, the three human modes, terminal width, and an optional animator
 * frame. Widgets describe <em>what</em>; this is the only object that knows <em>how</em>.
 *
 * <p>Nerd Font capability is a {@link NerdFontCaps} pair, not a flag — see {@link #wedge()} and
 * {@link #pill()}. The two axes have genuinely different font availability, so widgets must ask for
 * the one they paint.
 */
public record RenderContext(Theme theme, boolean ansi, NerdFontCaps nerd, int width, int frame) {

    public static final int DEFAULT_WIDTH = 80;

    public enum Mode {
        NERD,
        ANSI,
        PLAIN
    }

    public RenderContext {
        if (theme == null) theme = Theme.active();
        if (width <= 0) width = DEFAULT_WIDTH;
        if (nerd == null) nerd = NerdFontCaps.NONE;
        if (!ansi) nerd = NerdFontCaps.NONE;
        if (frame < 0) frame = 0;
    }

    /**
     * Snapshot of the process-wide theme / nerd caps / terminal columns. Called on every
     * animation frame, so the width comes from the {@link Size} cache — never a fresh
     * probe — and the caps come from {@link GlobalConfig#nerdFont()}, which memoizes for the life
     * of the process rather than re-detecting per frame.
     */
    public static RenderContext current() {
        Theme theme = Theme.active();
        boolean ansi = theme.isAnsi();
        NerdFontCaps caps = ansi ? GlobalConfig.nerdFont() : NerdFontCaps.NONE;
        return new RenderContext(theme, ansi, caps, Size.columns(), 0);
    }

    /** True when the solid powerline triangles {@code U+E0B0} / {@code U+E0B2} may be painted. */
    public boolean wedge() {
        return nerd.wedge();
    }

    /** True when the solid half-circle pill caps {@code U+E0B6} / {@code U+E0B4} may be painted. */
    public boolean pill() {
        return nerd.pill();
    }

    /**
     * Coarse three-way mode for fixture and diagnostic reporting only, where {@code NERD} means
     * "some PUA". Do not branch a widget's glyph choice on this — use {@link #wedge()} / {@link
     * #pill()}, or a font carrying only the classic Powerline set gets half its chrome as tofu.
     */
    public Mode mode() {
        if (!ansi) return Mode.PLAIN;
        if (nerd.any()) return Mode.NERD;
        return Mode.ANSI;
    }

    public RenderContext withFrame(int newFrame) {
        return new RenderContext(theme, ansi, nerd, width, newFrame);
    }

    /** All-or-nothing convenience: {@code true} grants both axes, {@code false} neither. */
    public RenderContext withNerd(boolean on) {
        return withCaps(on ? NerdFontCaps.ALL : NerdFontCaps.NONE);
    }

    /** Grant an explicit capability pair — the seam for exercising {@code wedge} / {@code pill}. */
    public RenderContext withCaps(NerdFontCaps caps) {
        return new RenderContext(theme, ansi, ansi ? caps : NerdFontCaps.NONE, width, frame);
    }

    public RenderContext withAnsi(boolean on) {
        return new RenderContext(theme, on, on ? nerd : NerdFontCaps.NONE, width, frame);
    }

    public RenderContext withWidth(int newWidth) {
        return new RenderContext(theme, ansi, nerd, newWidth, frame);
    }

    public static int skipEscape(String s, int i) {
        return Width.skipEscape(s, i);
    }

    /** Strip CSI and OSC alike (unterminated OSC consumes to end). */
    public static String stripAnsi(String s) {
        return Width.stripAnsi(s);
    }

    /** Visible terminal columns: CSI/OSC stripped, then wcwidth (CJK = 2). */
    public static int visibleWidth(String s) {
        return Width.columns(s);
    }

    // --- clipping ---------------------------------------------------------
    // Measuring an ANSI string and cutting one are the same scan, so they live with the same
    // owner: a cut that counts columns differently from visibleWidth lets a row exceed its
    // painted budget, wrap, and desync the live region's cursor bookkeeping.

    /**
     * Columns safe to paint on a single row of a {@code terminalCols}-wide terminal. Leaves the last
     * column free so a full-width write does not trip DEC auto-wrap (which can park the trailing
     * {@code …} on the next row where EL / the next tree line erase it).
     */
    public static int rowColumnBudget(int terminalCols) {
        if (terminalCols <= 1) return Math.max(1, terminalCols);
        return terminalCols - 1;
    }

    /**
     * Hard-truncate an ANSI-colored string to {@code maxCols} visible columns (never wraps). When
     * cut, ends with {@code …} so long test member names stay on one line. Copies escape sequences
     * without counting them and appends a reset if the text was cut.
     *
     * <p>Callers painting to a live TTY should pass {@link #rowColumnBudget(int)} of the terminal
     * width, not the raw column count — see that method.
     *
     * <p>Width metric: {@code WCWidth} per code point (CJK = 2 columns), matching
     * {@link #visibleWidth} and the resize reflow estimator — the two metrics
     * disagreeing inside one paint path let a CJK-heavy row exceed its painted budget, wrap, and
     * desync the cursor bookkeeping. WCWidth's tables are already linked into the
     * native image via {@code AttributedString.columnLength}; the ANSI scanning stays hand-rolled
     * ({@code AttributedString.fromAnsi} would add its parser, measured at +187–312 KB).
     * Surrogate pairs are consumed whole, so a cut can never emit a lone high surrogate.
     */
    public static String truncateVisible(String s, int maxCols) {
        if (maxCols <= 0) return "";
        // No maxCols==1 shortcut: the reserve logic below already handles it — a 1-column
        // string fits as-is, only longer input degrades to the bare ellipsis.
        int budget = maxCols;
        StringBuilder sb = new StringBuilder(s.length());
        int visible = 0;
        boolean truncated = false;
        boolean linkOpen = false;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\033') { // copy the whole escape (CSI or OSC) verbatim — zero columns
                int j = skipEscape(s, i);
                boolean unterminatedOsc = i + 1 < s.length()
                        && s.charAt(i + 1) == ']'
                        && !(j - 1 > i
                                && (s.charAt(j - 1) == '\u0007'
                                        || (j - 2 > i && s.charAt(j - 2) == '\u001b' && s.charAt(j - 1) == '\\')));
                // A live unterminated OSC must never reach the terminal — it would swallow the
                // following output up to the next BEL. Non-SGR CSI (cursor motion ESC[1A, erase
                // ESC[2K, …) from raw tool output would move the real cursor mid-region-paint
                // and desync row bookkeeping — drop it too; only SGR coloring passes (JK-2109).
                boolean motionCsi = i + 1 < s.length() && s.charAt(i + 1) == '[' && j > i + 1 && s.charAt(j - 1) != 'm';
                if (!unterminatedOsc && !motionCsi) {
                    sb.append(s, i, j);
                    // Track OSC-8 hyperlink state: a cut inside the linked label drops the close
                    // that follows it, and SGR RESET does not end a hyperlink — the ellipsis, EL,
                    // and later rows would all become part of the link.
                    int state = osc8LinkState(s, i, j);
                    if (state != 0) linkOpen = state > 0;
                }
                i = j;
            } else {
                // One code point per step (never splitting a surrogate pair), wcwidth columns.
                int cp = s.codePointAt(i);
                int cpLen = Character.charCount(cp);
                int w = Width.wcwidth(cp);
                if (w < 0) {
                    // C0/C1 controls (stray tab/backspace/CR in a step message — @DisplayName
                    // content flows in unsanitized). Emitting one at weight 0 advances real
                    // columns past the charged budget, wraps the row, and desyncs the cursor
                    // bookkeeping — drop it.
                    i += cpLen;
                    continue;
                }
                // Reserve one column for … only when the tail still costs columns.
                // need=1 ⇒ stop while the ellipsis still fits in budget.
                boolean moreAfter = hasVisibleFrom(s, i + cpLen);
                int need = moreAfter ? 1 : 0;
                if (visible + w + need > budget) {
                    truncated = true;
                    break;
                }
                sb.appendCodePoint(cp);
                visible += w;
                i += cpLen;
            }
        }
        if (truncated) {
            if (linkOpen) sb.append("\u001b]8;;\u0007"); // synthetic close before the ellipsis
            sb.append(Glyphs.ELLIPSIS);
            sb.append(Ansi.RESET);
        }
        return sb.toString();
    }

    /**
     * Classifies a complete escape at {@code s[i..j)}: {@code 1} when it opens an OSC-8 hyperlink
     * (non-empty URI), {@code -1} when it closes one (empty URI), {@code 0} for anything else.
     */
    private static int osc8LinkState(String s, int i, int j) {
        if (j - i < 5 || s.charAt(i + 1) != ']' || s.charAt(i + 2) != '8' || s.charAt(i + 3) != ';') {
            return 0;
        }
        int end = j;
        if (s.charAt(end - 1) == '\u0007') {
            end--;
        } else if (end - 2 >= i && s.charAt(end - 2) == '\u001b' && s.charAt(end - 1) == '\\') {
            end -= 2;
        }
        int semi = s.indexOf(';', i + 4); // end of the params section
        if (semi < 0 || semi >= end) return 0; // malformed OSC-8 — no URI section
        return semi + 1 < end ? 1 : -1;
    }

    /**
     * True when {@code s[from..]} still spends at least one terminal column — skips ANSI escapes
     * (CSI or OSC) and zero-/negative-width code points (combining marks, VS16, ZWJ, controls).
     * Feeds the ellipsis reserve: a tail that costs nothing (cafe + combining acute at budget 4,
     * emoji + VS16 at its exact width) must render fully, not lose its last glyph to a {@code …}.
     */
    static boolean hasVisibleFrom(String s, int from) {
        for (int i = from; i < s.length(); ) {
            if (s.charAt(i) == '\033') {
                i = skipEscape(s, i);
                continue;
            }
            int cp = s.codePointAt(i);
            if (Width.wcwidth(cp) > 0) return true;
            i += Character.charCount(cp);
        }
        return false;
    }
}
