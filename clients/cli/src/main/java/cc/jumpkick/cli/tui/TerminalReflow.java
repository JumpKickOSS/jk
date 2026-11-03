// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.config.TerminalIdentity;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Does this terminal rewrap already-painted lines when its width shrinks?
 *
 * <p>The answer decides how far {@code JkManagerView} climbs to wipe the live region after a
 * shrink: a reflowing terminal spreads the old paint over extra physical rows, so the
 * wipe must climb the <em>estimated</em> reflowed height; a clipping terminal keeps one physical
 * row per logical line, and climbing the reflow estimate there overshoots into completed output
 * above the region and erases it.
 *
 * <p>Env-only heuristic over {@link TerminalIdentity}, the identification {@code NerdFontDetect} uses. The default
 * is <b>clipping</b>: on an unknown terminal the failure mode is a cosmetic stale-row orphan,
 * never destroyed scrollback.
 */
final class TerminalReflow {

    private TerminalReflow() {}

    private static volatile @Nullable Boolean cached;

    /** Process-wide answer for the real environment (memoized). */
    static boolean reflows() {
        Boolean r = cached;
        if (r == null) {
            r = detect(System::getenv);
            cached = r;
        }
        return r;
    }

    /** Injectable for tests. */
    static boolean detect(Function<String, @Nullable String> env) {
        TerminalIdentity id = TerminalIdentity.of(env);
        // A multiplexer repaints its own grid, so the outer terminal's reflow says nothing about it.
        if (id.multiplexed()) return false;
        return switch (id.terminal()) {
            case GHOSTTY,
                    KITTY,
                    WEZTERM,
                    ITERM2,
                    APPLE_TERMINAL,
                    VSCODE, // xterm.js reflows
                    ALACRITTY,
                    WINDOWS_TERMINAL,
                    KONSOLE, // since 21.08
                    VTE, // gnome-terminal, xfce4-terminal, tilix, ...
                    FOOT -> true;
            default -> false;
        };
    }

    /**
     * Physical rows occupied by {@code lines} after the terminal reflows from {@code fromCols} to
     * {@code toCols}. Each line was painted at most {@link RenderContext#rowColumnBudget(int)} of
     * {@code fromCols} wide (one row then); after a shrink, reflow wraps that text to
     * {@code ceil(painted / toCols)} rows.
     */
    static int physicalRows(List<String> lines, int fromCols, int toCols) {
        if (lines == null || lines.isEmpty()) return 0;
        int width = Math.max(1, toCols);
        int fromBudget = RenderContext.rowColumnBudget(Math.max(1, fromCols));
        int rows = 0;
        for (String line : lines) {
            int painted = Math.min(RenderContext.visibleWidth(line), fromBudget);
            if (painted <= 0) {
                rows += 1; // blank logical line still occupies a row
            } else {
                rows += (painted + width - 1) / width;
            }
        }
        return rows;
    }

    /** Test hook: pin the answer ({@code null} re-detects from the real environment). */
    static void force(@Nullable Boolean value) {
        cached = value;
    }

    /** Test hook. */
    static void reset() {
        cached = null;
    }
}
