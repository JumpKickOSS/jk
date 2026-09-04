// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Looks up the font a given terminal is configured with. The <em>only</em> part of nerd-font
 * detection that touches the filesystem — {@link NerdFontDetect} reaches it solely for terminals it
 * has already identified and whose glyph coverage depends on the font.
 *
 * <p>Implemented as an interface so tests inject fonts directly instead of writing config files, and
 * so {@link NerdFontDetect} stays a pure function of (env, fonts).
 *
 * <p>Every method is best-effort: a missing file, an unreadable file, malformed contents, or an
 * unexpected shape all yield {@link Optional#empty()}. Nothing here may throw.
 */
public interface TerminalFonts {

    /** iTerm2's active profile font, from the {@code com.googlecode.iterm2} preferences domain. */
    Optional<String> itermFont();

    /** Alacritty's {@code font.normal.family}. */
    Optional<String> alacrittyFont();

    /** VS Code's {@code terminal.integrated.fontFamily}, else {@code editor.fontFamily}. */
    Optional<String> vscodeFont();

    /** Zed's {@code terminal.font_family}, else {@code buffer_font_family}. */
    Optional<String> zedFont();

    /** A source that finds nothing — the {@code default} for tests that only exercise T0-T2. */
    TerminalFonts NONE = new TerminalFonts() {
        @Override
        public Optional<String> itermFont() {
            return Optional.empty();
        }

        @Override
        public Optional<String> alacrittyFont() {
            return Optional.empty();
        }

        @Override
        public Optional<String> vscodeFont() {
            return Optional.empty();
        }

        @Override
        public Optional<String> zedFont() {
            return Optional.empty();
        }
    };

    /** The real thing, reading preferences and config files under the given environment. */
    static TerminalFonts real(Function<String, @Nullable String> env) {
        return new Real(env);
    }

    /** Reads the actual sources. Package-private so the interface stays the published surface. */
    final class Real implements TerminalFonts {

        private final Function<String, @Nullable String> env;

        Real(Function<String, @Nullable String> env) {
            this.env = env;
        }

        @Override
        public Optional<String> itermFont() {
            return MacPrefs.itermFontName(env);
        }

        /**
         * First existing candidate wins, matching Alacritty's own resolution order. {@code import}
         * chains are not followed (non-goal), so a font set only in an imported file reads
         * as absent.
         */
        @Override
        public Optional<String> alacrittyFont() {
            for (Path p : alacrittyCandidates()) {
                if (p != null && Files.isRegularFile(p)) {
                    return scanToml(p, "font.normal.family");
                }
            }
            return Optional.empty();
        }

        private List<Path> alacrittyCandidates() {
            Path configHome = xdgConfigHome();
            Path home = home();
            List<Path> out = new ArrayList<>(5);
            if (configHome != null) {
                out.add(configHome.resolve("alacritty/alacritty.toml"));
                out.add(configHome.resolve("alacritty.toml"));
            }
            if (home != null) {
                out.add(home.resolve(".config/alacritty/alacritty.toml"));
                out.add(home.resolve(".alacritty.toml"));
            }
            out.add(Path.of("/etc/alacritty/alacritty.toml"));
            return out;
        }

        @Override
        public Optional<String> vscodeFont() {
            Path settings = vscodeSettings();
            if (settings == null) return Optional.empty();
            return readJsonString(settings, "terminal.integrated.fontFamily")
                    .or(() -> readJsonString(settings, "editor.fontFamily"));
        }

        /**
         * VS Code vs VSCodium is told apart by {@code VSCODE_GIT_ASKPASS_NODE}, which points into
         * the running app bundle. Insiders / Cursor / Windsurf are out of scope, so an unrecognised
         * value reads as absent rather than guessing a directory.
         */
        private @Nullable Path vscodeSettings() {
            Path home = home();
            if (home == null) return null;
            String askpass = lower(env.apply("VSCODE_GIT_ASKPASS_NODE"));
            String appDir;
            if (askpass.contains("codium")) {
                appDir = "VSCodium";
            } else if (askpass.contains("code")) {
                appDir = "Code";
            } else {
                return null;
            }
            return Os.isDarwin()
                    ? home.resolve("Library/Application Support/" + appDir + "/User/settings.json")
                    : home.resolve(".config/" + appDir + "/User/settings.json");
        }

        /** Zed uses {@code ~/.config/zed} on every platform, macOS included. */
        @Override
        public Optional<String> zedFont() {
            Path home = home();
            if (home == null) return Optional.empty();
            Path settings = home.resolve(".config/zed/settings.json");
            return readJsonString(settings, "terminal", "font_family")
                    .or(() -> readJsonString(settings, "buffer_font_family"));
        }

        private @Nullable Path home() {
            String h = env.apply("HOME");
            if (h == null || h.isBlank()) h = System.getProperty("user.home");
            return (h == null || h.isBlank()) ? null : Path.of(h);
        }

        /** Honoured only when set, non-blank, and absolute — same rule the reference tool applies. */
        private @Nullable Path xdgConfigHome() {
            String x = env.apply("XDG_CONFIG_HOME");
            if (x == null || x.isBlank()) return null;
            Path p = Path.of(x);
            return p.isAbsolute() ? p : null;
        }

        private static String lower(@Nullable String s) {
            return s == null ? "" : s.toLowerCase(Locale.ROOT);
        }
    }

    /** Read one dotted scalar via the existing lenient line scanner. Never throws. */
    private static Optional<String> scanToml(Path file, String dottedKey) {
        try {
            String v = TomlScan.scan(file, dottedKey).get(dottedKey);
            return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v.trim());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Pluck a string from a JSONC settings file by walking {@code path} segments. Editor settings
     * carry {@code //} comments and trailing commas, hence the relaxed parse. A flat key containing
     * dots (VS Code's {@code "editor.fontFamily"}) is one segment, not a path — pass it as such.
     */
    private static Optional<String> readJsonString(Path file, String... path) {
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            Object node = MiniJson.parseRelaxed(Files.readString(file));
            for (String segment : path) {
                if (!(node instanceof Map<?, ?> map)) return Optional.empty();
                node = map.get(segment);
            }
            if (node instanceof String s && !s.isBlank()) return Optional.of(s.trim());
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
