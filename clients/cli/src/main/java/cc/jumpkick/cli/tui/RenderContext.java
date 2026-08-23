// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
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
}
