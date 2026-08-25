// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves {@code nerd-font = "auto"} into concrete {@link NerdFontCaps}.
 *
 * <p>Deny-by-default and env-first. Runs on <strong>every CLI launch</strong>, so the contract is
 * strict: no subprocesses, no terminal round-trips, and no file I/O at all unless an identified
 * terminal actually needs its config inspected. The tiers below short-circuit in order, and the
 * first four are pure environment reads.
 *
 * <p>Every failure mode degrades rather than throws — an unreadable, absent, or malformed config
 * source yields the tier's default, never an exception. Nerd-font detection must not be able to
 * fail a build.
 *
 * <p>Superseded the previous install-time-only probe, which shelled out to {@code fc-list} with a
 * two-second timeout and so could never run per-launch.
 */
public final class NerdFontDetect {

    private NerdFontDetect() {}

    /**
     * The outcome, with provenance. {@code source} is a short stable token for tests and
     * {@code jk self setup-terminal}; {@code reason} is human prose.
     */
    public record Result(NerdFontCaps caps, String source, String reason) {}

    /** Probe using the real process environment and the real config files. */
    public static Result detect() {
        return detect(System::getenv);
    }

    /** Probe over an env lookup, consulting the real config sources. */
    public static Result detect(Function<String, String> env) {
        return detect(env, TerminalFonts.real(env));
    }

    /**
     * Fully injectable probe. {@code fonts} supplies each terminal's configured font name, which is
     * the only part of this that touches the filesystem — tests pass a stub and stay hermetic.
     */
    public static Result detect(Function<String, String> env, TerminalFonts fonts) {
        // T0 — environments that never want PUA, whatever the font situation is.
        if (EnvValues.bool(env, "CI").orElse(false)) {
            return new Result(NerdFontCaps.NONE, "ci", "CI environment");
        }
        if ("dumb".equals(env.apply("TERM"))) {
            return new Result(NerdFontCaps.NONE, "term-dumb", "TERM=dumb");
        }

        String termProgram = lower(env.apply("TERM_PROGRAM"));
        String term = lower(env.apply("TERM"));

        // T1 — terminals that render our four codepoints regardless of the configured font,
        // because they bundle Nerd Font symbols (or draw the glyphs themselves).
        if (termProgram.equals("ghostty") || term.equals("xterm-ghostty")) {
            return new Result(NerdFontCaps.ALL, "bundled-terminal", "Ghostty bundles Nerd Font symbols");
        }
        if (termProgram.equals("kitty") || term.equals("xterm-kitty")) {
            return new Result(NerdFontCaps.ALL, "bundled-terminal", "kitty bundles Symbols Nerd Font");
        }
        if (termProgram.equals("wezterm")) {
            return new Result(NerdFontCaps.ALL, "bundled-terminal", "WezTerm bundles Nerd Font Symbols");
        }
        if (notBlank(env.apply("WT_SESSION"))) {
            // Windows Terminal draws U+E0B0-U+E0BF from an internal vector table (builtinGlyphs,
            // default on since 1.21), ignoring the configured font. That range covers all four of
            // our glyphs, so the font is irrelevant here — and this also covers WSL, since
            // WT_SESSION is deliberately forwarded through WSLENV.
            return new Result(NerdFontCaps.ALL, "builtin-glyphs", "Windows Terminal draws powerline glyphs itself");
        }

        // T2 — remote sessions. The font lives on the client, so any config file we can read here
        // describes the wrong machine. Ordered after T1 so a forwarded TERM=xterm-ghostty wins.
        if (notBlank(env.apply("SSH_TTY")) || notBlank(env.apply("SSH_CONNECTION"))) {
            return new Result(NerdFontCaps.NONE, "remote-session", "SSH session — client font is unknown");
        }

        // T3 — identified terminal whose font we can look up.
        if (termProgram.equals("apple_terminal")) {
            // Pinned to the wedge. Terminal.app stores its font as an NSKeyedArchiver blob, which
            // we deliberately do not decode; the triangles are the safe subset.
            return new Result(NerdFontCaps.WEDGE_ONLY, "apple-terminal", "Terminal.app — wedge glyphs only");
        }
        if (termProgram.equals("iterm.app")) {
            return fromFont(safe(fonts::itermFont), "iterm2", "iTerm2");
        }
        if (notBlank(env.apply("ALACRITTY_LOG"))
                || notBlank(env.apply("ALACRITTY_WINDOW_ID"))
                || notBlank(env.apply("ALACRITTY_SOCKET"))) {
            // Alacritty draws the classic Powerline set itself, so the triangles land whatever the
            // configured font is — the same floor as Terminal.app. It does not cover Powerline
            // Extra, so the semi-circles still have to be earned from the font.
            return fromFont(safe(fonts::alacrittyFont), "alacritty", "Alacritty", NerdFontCaps.WEDGE_ONLY);
        }
        if (termProgram.equals("vscode")) {
            return fromFont(safe(fonts::vscodeFont), "vscode", "VS Code");
        }
        if (termProgram.equals("zed")) {
            return fromFont(safe(fonts::zedFont), "zed", "Zed");
        }

        // T4 — unknown. Prefer no PUA over tofu.
        return new Result(
                NerdFontCaps.NONE,
                termProgram.isEmpty() ? "unknown-terminal" : "no-resolver",
                termProgram.isEmpty()
                        ? "no terminal identified — defaulting to no PUA glyphs"
                        : "no font lookup for TERM_PROGRAM=" + termProgram);
    }

    /**
     * Read one font source without trusting it. The bundled {@link TerminalFonts.Real} already
     * degrades internally, but {@code fonts} is injectable and this is on the per-launch path of
     * every build — a source that throws, or breaks its contract by returning {@code null}, must
     * cost us the glyphs and nothing more.
     */
    private static Optional<String> safe(Supplier<Optional<String>> source) {
        try {
            Optional<String> v = source.get();
            return v == null ? Optional.empty() : v;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Turn a looked-up font name into caps, keeping the font in the reason for {@code --explain}. */
    private static Result fromFont(Optional<String> font, String source, String label) {
        return fromFont(font, source, label, NerdFontCaps.NONE);
    }

    /**
     * As {@link #fromFont(Optional, String, String)} but for a terminal that renders some glyphs
     * from its own tables: {@code floor} is granted unconditionally and the font can only add to it.
     * A font we cannot read therefore costs the upgrade, never the floor.
     */
    private static Result fromFont(Optional<String> font, String source, String label, NerdFontCaps floor) {
        if (font.isEmpty()) {
            return new Result(floor, source, label + " — font not determined");
        }
        String name = font.get();
        NerdFontCaps caps = NerdFontNames.caps(name).max(floor);
        return new Result(caps, source, label + " font " + name);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
