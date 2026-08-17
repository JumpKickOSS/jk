// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.Locale;
import java.util.function.Function;

/**
 * Does this terminal rewrap already-painted lines when its width shrinks?
 *
 * <p>The answer decides how far {@code JkManagerView} climbs to wipe the live region after a
 * shrink: a reflowing terminal spreads the old paint over extra physical rows, so the
 * wipe must climb the <em>estimated</em> reflowed height; a clipping terminal keeps one physical
 * row per logical line, and climbing the reflow estimate there overshoots into completed output
 * above the region and erases it.
 *
 * <p>Env-only heuristic, same identification signals {@code NerdFontDetect} trusts. The default
 * is <b>clipping</b>: on an unknown terminal the failure mode is a cosmetic stale-row orphan,
 * never destroyed scrollback.
 */
final class TerminalReflow {

    private TerminalReflow() {}

    private static volatile Boolean cached;

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
    static boolean detect(Function<String, String> env) {
        // VTE family (gnome-terminal, xfce4-terminal, tilix, …) rewraps on resize.
        if (notBlank(env.apply("VTE_VERSION"))) return true;
        // Windows Terminal rewraps.
        if (notBlank(env.apply("WT_SESSION"))) return true;
        // Konsole rewraps (since 21.08); it exports KONSOLE_VERSION and no TERM_PROGRAM.
        if (notBlank(env.apply("KONSOLE_VERSION"))) return true;
        String termProgram = lower(env.apply("TERM_PROGRAM"));
        String term = lower(env.apply("TERM"));
        switch (termProgram) {
            case "ghostty", "kitty", "wezterm", "iterm.app", "apple_terminal", "vscode" -> {
                return true; // all rewrap (VS Code = xterm.js, which reflows)
            }
            default -> {
                /* fall through to TERM */
            }
        }
        // Alacritty rewraps (since 0.3.0): TERM=alacritty[-direct]; newer builds also set
        // TERM_PROGRAM=alacritty.
        if (term.startsWith("alacritty") || termProgram.startsWith("alacritty")) return true;
        if (term.equals("xterm-kitty") || term.equals("xterm-ghostty") || term.equals("foot")) return true;
        // xterm, linux console, screen, tmux, unknown: treat as clipping.
        return false;
    }

    /** Test hook: pin the answer ({@code null} re-detects from the real environment). */
    static void force(Boolean value) {
        cached = value;
    }

    /** Test hook. */
    static void reset() {
        cached = null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
