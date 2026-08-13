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
     * Visible terminal columns: CSI/OSC stripped, then wcwidth (CJK = 2). Shared by every widget
     * that pads cells or fills a title bar.
     */
    public static int visibleWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        return new AttributedString(AttributedString.stripAnsi(s)).columnLength();
    }
}
