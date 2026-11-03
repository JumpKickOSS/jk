// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.config.TerminalCapsCache.Check;
import cc.jumpkick.config.TerminalCapsCache.Fingerprint;
import cc.jumpkick.config.TerminalIdentity.Terminal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code nerd-font = "auto"} into concrete {@link NerdFontCaps}.
 *
 * <p>Deny-by-default. Runs on <strong>every CLI launch</strong>, so the contract is strict: no
 * subprocesses and no terminal round-trips. The tiers short-circuit in order:
 *
 * <ol>
 *   <li>environments that never want PUA glyphs (CI, {@code TERM=dumb});
 *   <li>terminals that bundle or draw the glyphs, recognised from the environment alone;
 *   <li>what the user confirmed in {@code jk self setup-terminal}, while the terminal's
 *       {@link TerminalCapsCache.Fingerprint fingerprint} still matches;
 *   <li>the terminal's configured font and the Nerd Fonts installed on the machine.
 * </ol>
 *
 * <p>Every failure mode degrades rather than throws — an unreadable, absent, or malformed source
 * yields the tier's default, never an exception. Nerd-font detection must not be able to fail a
 * build.
 */
public final class NerdFontDetect {

    private NerdFontDetect() {}

    /** How much to trust a {@link Result}. */
    public enum Confidence {
        /** The user confirmed it for this terminal and nothing has changed since. */
        CONFIRMED,
        /** The terminal or its configured font states it. */
        KNOWN,
        /** Inferred from fonts installed on the machine, or a floor the terminal guarantees. */
        INFERRED,
        /** Nothing identified the terminal or its font; glyphs are off. */
        UNKNOWN
    }

    /** A stored confirmation that no longer matches this terminal. */
    public record Stale(String slot, Fingerprint current, List<String> changed) {}

    /**
     * The outcome, with provenance. {@code source} is a short stable token for tests and
     * {@code jk self setup-terminal}; {@code reason} is human prose; {@code cache} describes the
     * confirmation cache state for {@code --explain}; {@code stale} is set when a stored confirmation
     * was rejected.
     */
    public record Result(
            NerdFontCaps caps,
            String source,
            String reason,
            Confidence confidence,
            String cache,
            @Nullable Stale stale) {

        Result(NerdFontCaps caps, String source, String reason, Confidence confidence) {
            this(caps, source, reason, confidence, "not consulted", null);
        }

        private Result withCache(String cache, @Nullable Stale stale) {
            return new Result(caps, source, reason, confidence, cache, stale);
        }
    }

    /** Probe using the real process environment and the real config files. */
    public static Result detect() {
        return detect(BuildEnv::get);
    }

    /** Probe over an env lookup, consulting the real config sources. */
    public static Result detect(Function<String, @Nullable String> env) {
        return detect(env, TerminalFonts.real(env), InstalledFonts.real(env), TerminalCapsCache.real());
    }

    /** Probe with injected font sources and no confirmation cache — the tiers other than the cache. */
    public static Result detect(Function<String, @Nullable String> env, TerminalFonts fonts) {
        return detect(env, fonts, InstalledFonts.NONE_INSTALLED, TerminalCapsCache.none());
    }

    /** Fully injectable probe; tests pass stubs and stay hermetic. */
    public static Result detect(
            Function<String, @Nullable String> env,
            TerminalFonts fonts,
            InstalledFonts installed,
            TerminalCapsCache cache) {
        // Environments that never want PUA, whatever the font situation is.
        if (EnvValues.isCi(env)) {
            return new Result(NerdFontCaps.NONE, "ci", "CI environment", Confidence.KNOWN);
        }
        if ("dumb".equals(env.apply("TERM"))) {
            return new Result(NerdFontCaps.NONE, "term-dumb", "TERM=dumb", Confidence.KNOWN);
        }

        TerminalIdentity id = TerminalIdentity.of(env);

        // Terminals that render our four codepoints regardless of the configured font, because they
        // bundle Nerd Font symbols or draw the glyphs themselves. Environment only; no I/O.
        switch (id.terminal()) {
            case GHOSTTY -> {
                return bundled("bundled-terminal", "Ghostty bundles Nerd Font symbols");
            }
            case KITTY -> {
                return bundled("bundled-terminal", "kitty bundles Symbols Nerd Font");
            }
            case WEZTERM -> {
                return bundled("bundled-terminal", "WezTerm bundles Nerd Font Symbols");
            }
            case WINDOWS_TERMINAL -> {
                // builtinGlyphs (default on since 1.21) draws U+E0B0-U+E0BF from an internal vector
                // table, ignoring the configured font; that range covers all four of our glyphs.
                return bundled("builtin-glyphs", "Windows Terminal draws powerline glyphs itself");
            }
            default -> {
                /* resolved below */
            }
        }

        // What the user confirmed for this terminal, if it still holds.
        String slot = TerminalCapsCache.slot(id, env);
        Optional<TerminalCapsCache.Entry> stored = cache.read(slot);
        TerminalFonts.Lookup font = TerminalFonts.Lookup.EMPTY;
        InstalledFonts.Scan scan = null;
        String cacheState = "no confirmation stored";
        Stale stale = null;
        if (stored.isPresent()) {
            font = safeLookup(fonts, id.terminal());
            scan = safeScan(installed);
            Fingerprint fp = TerminalCapsCache.fingerprint(id, font, scan);
            Check check = cache.check(slot, fp);
            if (check instanceof Check.Valid valid) {
                var e = valid.entry();
                return new Result(
                        e.caps(),
                        "confirmed",
                        "confirmed in setup-terminal on " + e.at(),
                        Confidence.CONFIRMED,
                        "valid (" + slot + ")",
                        null);
            }
            if (check instanceof Check.Stale s) {
                stale = new Stale(slot, fp, s.changed());
                cacheState = "stale, " + String.join(", ", s.changed()) + " changed (" + slot + ")";
            }
        }

        Result r = heuristic(id, fonts, installed, font, scan);
        return r.withCache(cacheState, stale);
    }

    /** Record the user's answer for the current terminal under its current fingerprint. */
    public static boolean confirm(
            Function<String, @Nullable String> env,
            TerminalFonts fonts,
            InstalledFonts installed,
            TerminalCapsCache cache,
            NerdFontCaps caps,
            String source) {
        TerminalIdentity id = TerminalIdentity.of(env);
        Fingerprint fp = TerminalCapsCache.fingerprint(id, safeLookup(fonts, id.terminal()), safeScan(installed));
        return cache.put(TerminalCapsCache.slot(id, env), caps, source, fp);
    }

    /** {@link #confirm} against the real environment, fonts and cache. */
    public static boolean confirm(NerdFontCaps caps, String source) {
        Function<String, @Nullable String> env = BuildEnv::get;
        return confirm(env, TerminalFonts.real(env), InstalledFonts.real(env), TerminalCapsCache.real(), caps, source);
    }

    /**
     * Report a stale confirmation once per change: true the first time, false afterwards. Reads and
     * writes the cache, so callers invoke it where a one-line hint can actually be shown.
     */
    public static boolean claimStaleHint(Result result, TerminalCapsCache cache) {
        Stale s = result.stale();
        return s != null && cache.claimHint(s.slot(), s.current());
    }

    /** {@link #claimStaleHint} against the real cache. */
    public static boolean claimStaleHint(Result result) {
        return claimStaleHint(result, TerminalCapsCache.real());
    }

    private static Result bundled(String source, String reason) {
        return new Result(NerdFontCaps.ALL, source, reason, Confidence.KNOWN);
    }

    /** Tiers that read the terminal's own font and the installed fonts. */
    private static Result heuristic(
            TerminalIdentity id,
            TerminalFonts fonts,
            InstalledFonts installed,
            TerminalFonts.Lookup preFont,
            InstalledFonts.@Nullable Scan preScan) {
        Terminal t = id.terminal();

        if (id.remote()) {
            // The font lives on the client, so any file we can read here describes the wrong machine.
            return new Result(
                    NerdFontCaps.NONE, "remote-session", "SSH session; the client font is unknown", Confidence.UNKNOWN);
        }

        if (t == Terminal.APPLE_TERMINAL) {
            // Pinned off, not to the wedge: Terminal.app draws no powerline glyph itself, and its
            // font is an NSKeyedArchiver blob we deliberately do not decode. Someone who patches
            // their font confirms it in `jk self setup-terminal`.
            return new Result(
                    NerdFontCaps.NONE,
                    "apple-terminal",
                    "Terminal.app draws no powerline glyphs itself",
                    Confidence.KNOWN);
        }

        // Terminals that draw only from the configured font.
        if (t == Terminal.ITERM2 || t == Terminal.VSCODE || t == Terminal.ZED) {
            String label =
                    switch (t) {
                        case ITERM2 -> "iTerm2";
                        case VSCODE -> "VS Code";
                        default -> "Zed";
                    };
            return fromFont(lookup(fonts, t, preFont), t.id(), label, NerdFontCaps.NONE);
        }

        // Terminals that fall back to any installed font for a missing glyph.
        if (fallsBack(t)) {
            NerdFontCaps floor = t == Terminal.ALACRITTY ? NerdFontCaps.WEDGE_ONLY : NerdFontCaps.NONE;
            Result byFont = fromFont(lookup(fonts, t, preFont), t.id(), label(t), floor);
            if (byFont.caps().wedge() && byFont.caps().pill()) return byFont;
            InstalledFonts.Scan scan = preScan != null ? preScan : safeScan(installed);
            NerdFontCaps merged = byFont.caps().max(scan.caps());
            if (merged.equals(byFont.caps())) return byFont;
            return new Result(
                    merged,
                    "installed-fonts",
                    label(t) + " falls back to installed fonts; one is a Nerd Font",
                    Confidence.INFERRED);
        }

        // Unidentified: a Nerd Font on disk is a weak signal, so grant only the triangles.
        InstalledFonts.Scan scan = preScan != null ? preScan : safeScan(installed);
        if (scan.caps().wedge()) {
            return new Result(
                    NerdFontCaps.WEDGE_ONLY,
                    "installed-fonts",
                    "terminal not identified; Nerd Font installed, triangles only",
                    Confidence.INFERRED);
        }
        return new Result(
                NerdFontCaps.NONE,
                t == Terminal.UNKNOWN ? "unknown-terminal" : "no-resolver",
                t == Terminal.UNKNOWN ? "no terminal identified; glyphs stay off" : "no Nerd Font found for " + t.id(),
                Confidence.UNKNOWN);
    }

    private static boolean fallsBack(Terminal t) {
        return switch (t) {
            case ALACRITTY, KONSOLE, VTE, FOOT, RIO, WARP, TABBY, HYPER -> true;
            default -> false;
        };
    }

    private static String label(Terminal t) {
        return switch (t) {
            case ALACRITTY -> "Alacritty";
            case KONSOLE -> "Konsole";
            case VTE -> "VTE terminal";
            case FOOT -> "foot";
            case RIO -> "Rio";
            case WARP -> "Warp";
            case TABBY -> "Tabby";
            case HYPER -> "Hyper";
            default -> t.id();
        };
    }

    private static TerminalFonts.Lookup lookup(TerminalFonts fonts, Terminal t, TerminalFonts.Lookup pre) {
        return pre != TerminalFonts.Lookup.EMPTY ? pre : safeLookup(fonts, t);
    }

    /**
     * Read one font source without trusting it. {@code fonts} is injectable and this is on the
     * per-launch path of every build — a source that throws, or returns {@code null}, must cost us
     * the glyphs and nothing more.
     */
    private static TerminalFonts.Lookup safeLookup(TerminalFonts fonts, Terminal t) {
        try {
            TerminalFonts.Lookup l = fonts.lookup(t);
            return l == null || l.font() == null ? TerminalFonts.Lookup.EMPTY : l;
        } catch (Throwable e) {
            return TerminalFonts.Lookup.EMPTY;
        }
    }

    private static InstalledFonts.Scan safeScan(InstalledFonts installed) {
        try {
            InstalledFonts.Scan s = installed.scan();
            return s == null ? InstalledFonts.Scan.NONE : s;
        } catch (Throwable e) {
            return InstalledFonts.Scan.NONE;
        }
    }

    /**
     * Turn a looked-up font name into caps, keeping the font in the reason for {@code --explain}.
     * {@code floor} is granted unconditionally and the font can only add to it, so a font we cannot
     * read costs the upgrade, never the floor.
     */
    private static Result fromFont(TerminalFonts.Lookup lookup, String source, String label, NerdFontCaps floor) {
        Optional<String> font = lookup.font();
        if (font.isEmpty()) {
            return new Result(
                    floor,
                    source,
                    label + " — font not determined",
                    floor.any() ? Confidence.INFERRED : Confidence.UNKNOWN);
        }
        String name = font.get();
        NerdFontCaps caps = NerdFontNames.caps(name).max(floor);
        return new Result(caps, source, label + " font " + name, Confidence.KNOWN);
    }
}
