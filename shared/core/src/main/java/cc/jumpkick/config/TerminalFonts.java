// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.config.TerminalIdentity.Terminal;
import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Looks up the font a given terminal is configured with, plus a stamp of the file it came from. The
 * part of nerd-font detection that touches the filesystem: {@link NerdFontDetect} asks only for
 * terminals it has identified and whose glyph coverage depends on the font.
 *
 * <p>Terminals that bundle or draw the glyphs themselves (Ghostty, kitty, WezTerm, Windows
 * Terminal) need no reader. GNOME Terminal and the other VTE terminals keep their font in a binary
 * dconf database that is deliberately not decoded; they resolve from {@link InstalledFonts}.
 *
 * <p>Implemented as an interface so tests inject fonts directly instead of writing config files.
 * Every lookup is best-effort: a missing file, unreadable file, malformed contents, or unexpected
 * shape all yield an empty font. Nothing here may throw.
 */
public interface TerminalFonts {

    /**
     * One lookup. {@code font} is the raw configured family (possibly a comma-separated stack);
     * {@code stamp} identifies the file it came from ({@code path:mtime:size}), empty when the source
     * is not a file or was not found.
     */
    record Lookup(Optional<String> font, String stamp) {
        public static final Lookup EMPTY = new Lookup(Optional.empty(), "");

        public static Lookup of(String font) {
            return new Lookup(Optional.of(font), "");
        }
    }

    /** The configured font of {@code terminal}. */
    Lookup lookup(Terminal terminal);

    /** A source that finds nothing — for tests that only exercise the environment tiers. */
    TerminalFonts NONE = terminal -> Lookup.EMPTY;

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
        public Lookup lookup(Terminal terminal) {
            try {
                return switch (terminal) {
                    case ITERM2 -> new Lookup(MacPrefs.itermFontName(env), "");
                    case ALACRITTY -> alacritty();
                    case VSCODE -> vscode();
                    case ZED -> zed();
                    case KONSOLE -> konsole();
                    case FOOT -> foot();
                    case RIO -> rio();
                    case HYPER -> hyper();
                    default -> Lookup.EMPTY;
                };
            } catch (RuntimeException e) {
                return Lookup.EMPTY;
            }
        }

        /**
         * First existing candidate wins, matching Alacritty's own resolution order. {@code import}
         * chains are not followed, so a font set only in an imported file reads as absent.
         */
        private Lookup alacritty() {
            Path configHome = xdgConfigHome();
            Path home = home();
            List<Path> candidates = new ArrayList<>(5);
            if (configHome != null) {
                candidates.add(configHome.resolve("alacritty/alacritty.toml"));
                candidates.add(configHome.resolve("alacritty.toml"));
            }
            if (home != null) {
                candidates.add(home.resolve(".config/alacritty/alacritty.toml"));
                candidates.add(home.resolve(".alacritty.toml"));
            }
            candidates.add(Path.of("/etc/alacritty/alacritty.toml"));
            for (Path p : candidates) {
                if (Files.isRegularFile(p)) return new Lookup(scanToml(p, "font.normal.family"), stamp(p));
            }
            return Lookup.EMPTY;
        }

        private Lookup vscode() {
            Path settings = vscodeSettings();
            if (settings == null) return Lookup.EMPTY;
            return new Lookup(
                    readJsonString(settings, "terminal.integrated.fontFamily")
                            .or(() -> readJsonString(settings, "editor.fontFamily")),
                    stamp(settings));
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
        private Lookup zed() {
            Path home = home();
            if (home == null) return Lookup.EMPTY;
            Path settings = home.resolve(".config/zed/settings.json");
            return new Lookup(
                    readJsonString(settings, "terminal", "font_family")
                            .or(() -> readJsonString(settings, "buffer_font_family")),
                    stamp(settings));
        }

        /** The default profile named in {@code konsolerc}, then that profile's {@code [Appearance] Font}. */
        private Lookup konsole() {
            Path configHome = configHome();
            Path dataHome = dataHome();
            if (configHome == null || dataHome == null) return Lookup.EMPTY;
            Path rc = configHome.resolve("konsolerc");
            Optional<String> profile = readIni(rc, "Desktop Entry", "DefaultProfile");
            if (profile.isEmpty()) return Lookup.EMPTY;
            Path file = dataHome.resolve("konsole").resolve(profile.get());
            // "Font=Hack Nerd Font,12,-1,5,50,0,0,0,0,0": the family is the first field.
            Optional<String> font = readIni(file, "Appearance", "Font").map(v -> v.split(",", 2)[0].trim());
            return new Lookup(font.filter(v -> !v.isEmpty()), stamp(rc) + "|" + stamp(file));
        }

        /** foot's {@code font=Family:size=12, Fallback:size=12}, attributes dropped. */
        private Lookup foot() {
            Path configHome = configHome();
            if (configHome == null) return Lookup.EMPTY;
            Path file = configHome.resolve("foot/foot.ini");
            Optional<String> font = readIni(file, "main", "font").or(() -> readIni(file, "", "font"));
            return new Lookup(font.map(Real::dropAttributes).filter(v -> !v.isEmpty()), stamp(file));
        }

        /** Rio's {@code [fonts] family}. */
        private Lookup rio() {
            Path configHome = configHome();
            if (configHome == null) return Lookup.EMPTY;
            Path file = configHome.resolve("rio/config.toml");
            if (!Files.isRegularFile(file)) return Lookup.EMPTY;
            return new Lookup(scanToml(file, "fonts.family"), stamp(file));
        }

        /** Hyper's {@code ~/.hyper.js} is JavaScript; only a literal {@code fontFamily: '...'} is read. */
        private Lookup hyper() {
            Path home = home();
            if (home == null) return Lookup.EMPTY;
            Path file = home.resolve(".hyper.js");
            try {
                if (!Files.isRegularFile(file)) return Lookup.EMPTY;
                Matcher m = HYPER_FONT.matcher(Files.readString(file));
                if (!m.find()) return new Lookup(Optional.empty(), stamp(file));
                String raw = m.group(1) != null ? m.group(1) : m.group(2);
                String family = raw.replace("\"", "").replace("'", "").trim();
                return new Lookup(family.isEmpty() ? Optional.empty() : Optional.of(family), stamp(file));
            } catch (IOException | RuntimeException e) {
                return Lookup.EMPTY;
            }
        }

        private static final Pattern HYPER_FONT = Pattern.compile("fontFamily\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\")");

        /** {@code "Family:size=12, Other:size=11"} to {@code "Family, Other"}. */
        private static String dropAttributes(String stack) {
            StringBuilder out = new StringBuilder();
            for (String entry : stack.split(",")) {
                String family = entry.split(":", 2)[0].trim();
                if (family.isEmpty()) continue;
                if (out.length() > 0) out.append(", ");
                out.append(family);
            }
            return out.toString();
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

        private @Nullable Path configHome() {
            Path x = xdgConfigHome();
            Path home = home();
            return x != null ? x : home == null ? null : home.resolve(".config");
        }

        private @Nullable Path dataHome() {
            String x = env.apply("XDG_DATA_HOME");
            if (x != null && !x.isBlank() && Path.of(x).isAbsolute()) return Path.of(x);
            Path home = home();
            return home == null ? null : home.resolve(".local/share");
        }

        private static String lower(@Nullable String s) {
            return s == null ? "" : s.toLowerCase(Locale.ROOT);
        }
    }

    /** {@code path:mtime:size}, or empty when the file is absent. */
    private static String stamp(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "";
            return file + ":" + Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * One {@code key=value} from an INI file; {@code section} empty means before the first header.
     * Comment lines ({@code #}, {@code ;}) are skipped. Never throws.
     */
    private static Optional<String> readIni(Path file, String section, String key) {
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            String current = "";
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = line.substring(1, line.length() - 1).strip();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0 || !current.equals(section)) continue;
                if (line.substring(0, eq).strip().equals(key)) {
                    String v = line.substring(eq + 1).strip();
                    return v.isEmpty() ? Optional.empty() : Optional.of(v);
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
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
