// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Machine-scoped preferences from {@code ~/.config/jk/config.toml}: root-level UI flags (e.g.
 * {@code nerd-font}) and global {@code [repositories]}. Not project-overridable; env overrides
 * apply. Project {@code [repositories]} win on name collision; global fills gaps.
 */
public final class GlobalConfig {

    private GlobalConfig() {}

    /**
     * Which Nerd Font glyph families may be painted. Precedence, highest first:
     *
     * <ol>
     *   <li>the color/ANSI gate — {@code --no-ansi}, {@code TERM=dumb}, {@code CI}, {@code NO_COLOR},
     *       {@code --color never}. Absolute: beats even an explicit env override.
     *   <li>env {@code JK_NERD_FONT} — the full value set, including the mode words.
     *   <li>env {@code NERD_FONT} — the host-wide cross-tool variable, booleans only.
     *   <li>{@code ~/.config/jk/config.toml} root-level {@code nerd-font}.
     *   <li>default {@code "auto"} → {@link NerdFontDetect}.
     * </ol>
     *
     * <p>Memoized for the life of the process. This is load-bearing, not an optimization:
     * {@code RenderContext.current()} calls it on every animation frame, and {@code auto} can reach
     * the filesystem.
     */
    public static NerdFontCaps nerdFont() {
        NerdFontCaps hit = resolvedNerdFont;
        if (hit != null) return hit;
        NerdFontCaps fresh = nerdFont(
                JkDirs.userConfigFile(),
                System.getenv("JK_NERD_FONT"),
                System.getenv("NERD_FONT"),
                colorActivelyEnabled());
        resolvedNerdFont = fresh;
        return fresh;
    }

    /** The process-wide resolved value; see {@link #nerdFont()}. Cleared by {@link #clearCache()}. */
    private static volatile NerdFontCaps resolvedNerdFont;

    /**
     * True when color output is currently enabled — same logic as {@code Theme.colorEnabled} in
     * the CLI layer, duplicated here so {@code kernel/core} can apply it without a circular dep.
     */
    static boolean colorActivelyEnabled() {
        // No-ANSI triggers: --no-ansi flag, TERM=dumb, CI=true/1.
        if (cc.jumpkick.config.SessionContext.current().config().noAnsiOr(false)) return false;
        if ("dumb".equals(System.getenv("TERM"))) return false;
        String ci = System.getenv("CI");
        if ("true".equalsIgnoreCase(ci) || "1".equals(ci)) return false;
        var choice = cc.jumpkick.config.SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> {
                String nc = System.getenv("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }

    /** As {@link #nerdFont()} but against an explicit config file — for tests. */
    static NerdFontCaps nerdFont(Path configFile) {
        return nerdFont(configFile, System.getenv("JK_NERD_FONT"), System.getenv("NERD_FONT"), colorActivelyEnabled());
    }

    /** As {@link #nerdFont(Path)} but with explicit env values — bypasses the color gate for tests. */
    static NerdFontCaps nerdFont(Path configFile, String jkEnv, String hostEnv) {
        return nerdFont(configFile, jkEnv, hostEnv, true);
    }

    /**
     * Full testable overload: config file + both env values + explicit color-enabled flag. Resolves
     * {@code auto} against the real environment; {@link #nerdFontMode} is the seam for tests that
     * need to pin detection.
     */
    static NerdFontCaps nerdFont(Path configFile, String jkEnv, String hostEnv, boolean colorEnabled) {
        if (!colorEnabled) return NerdFontCaps.NONE;
        NerdFontMode mode = nerdFontMode(configFile, jkEnv, hostEnv);
        return mode == NerdFontMode.AUTO ? NerdFontDetect.detect().caps() : mode.fixedCaps();
    }

    /**
     * The declared mode, before {@code auto} is resolved. Split out so {@code jk self setup-terminal}
     * can report what was asked for separately from what was detected.
     */
    static NerdFontMode nerdFontMode(Path configFile, String jkEnv, String hostEnv) {
        return NerdFontMode.parse(jkEnv)
                .or(() -> NerdFontMode.parseBooleanOnly(hostEnv))
                .or(() -> stringFromRoot(configFile, "nerd-font").flatMap(NerdFontMode::parse))
                .orElse(NerdFontMode.AUTO);
    }

    /**
     * Engine-host JDK pin ({@code JK_ENGINE_JDK} → {@code [toolchain].jdk}), independent of
     * project {@code jdk}. Empty when unset.
     */
    public static Optional<String> engineJdkPin() {
        return engineJdkPin(JkDirs.userConfigFile(), System.getenv("JK_ENGINE_JDK"));
    }

    /** As {@link #engineJdkPin()} but against an explicit config file + env value — for tests. */
    static Optional<String> engineJdkPin(Path file, String envValue) {
        if (envValue != null && !envValue.isBlank()) return Optional.of(envValue.trim());
        return stringFromGlobal(file, "toolchain", "jdk");
    }

    /** {@code [release] trusted-keys}: base64 Ed25519 SPKI keys (extends baked-in trust). */
    public static List<String> releaseTrustedKeys() {
        return stringFromGlobal(JkDirs.userConfigFile(), "release", "trusted-keys")
                .map(v -> Arrays.stream(v.split(","))
                        .map(String::trim)
                        .filter(k -> !k.isEmpty())
                        .toList())
                .orElse(List.of());
    }

    /** Read a single top-level string value, leniently, via TomlScan. */
    private static Optional<String> stringFromRoot(Path file, String key) {
        return stringFromGlobal(file, key);
    }

    /** Read a single string value from {@code [table].key} (or a bare top-level key), leniently. */
    private static Optional<String> stringFromGlobal(Path file, String table, String key) {
        return stringFromGlobal(file, table + "." + key);
    }

    /** Read a dotted or bare key via TomlScan, leniently. */
    private static Optional<String> stringFromGlobal(Path file, String dotted) {
        if (file == null) return Optional.empty();
        String cacheKey;
        long size;
        long modified;
        try {
            if (!Files.exists(file)) return Optional.empty();
            var attrs = Files.readAttributes(file, BasicFileAttributes.class);
            cacheKey = file + "|" + dotted;
            size = attrs.size();
            modified = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return Optional.empty();
        }
        return memoized(
                        SCAN_CACHE,
                        cacheKey,
                        size,
                        modified,
                        () -> Optional.ofNullable(TomlScan.scan(file, dotted).get(dotted)))
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }

    /**
     * One entry per (file, dotted key), carrying the size+mtime stamp it was scanned at.
     *
     * <p>The stamp lives in the value, not the key: with it in the key every config rewrite minted
     * a new entry and nothing ever removed the old one — and this cache had no clear path at all,
     * so it grew for the life of the process.
     */
    private static final ConcurrentHashMap<String, Stamped<Optional<String>>> SCAN_CACHE = new ConcurrentHashMap<>();

    // Memoize per path, revalidated on size+mtime (file is process-stable; nerdfont hits this often).
    private static final ConcurrentHashMap<String, Stamped<Optional<TomlParseResult>>> CONFIG_CACHE =
            new ConcurrentHashMap<>();

    /** A memoized value plus the file stamp it was computed from. */
    private record Stamped<T>(long size, long modifiedMillis, T value) {
        boolean matches(long otherSize, long otherModified) {
            return size == otherSize && modifiedMillis == otherModified;
        }
    }

    /** Look up {@code key}, recomputing when the file's stamp moved; one entry per key, replaced. */
    private static <T> T memoized(
            ConcurrentHashMap<String, Stamped<T>> cache,
            String key,
            long size,
            long modifiedMillis,
            Supplier<T> compute) {
        Stamped<T> hit = cache.get(key);
        if (hit != null && hit.matches(size, modifiedMillis)) return hit.value();
        T fresh = compute.get();
        cache.put(key, new Stamped<>(size, modifiedMillis, fresh));
        return fresh;
    }

    private static Optional<TomlParseResult> parseConfig(Path file) {
        if (file == null) return Optional.empty();
        String key;
        long size;
        long modified;
        try {
            if (!Files.exists(file)) return Optional.empty();
            var attrs = Files.readAttributes(file, BasicFileAttributes.class);
            key = file.toAbsolutePath().toString();
            size = attrs.size();
            modified = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return TomlValues.parse(file); // uncached fallback on stat failure
        }
        return memoized(CONFIG_CACHE, key, size, modified, () -> TomlValues.parse(file));
    }

    /** Clear the memoized config parse. For tests that rewrite {@code ~/.config/jk/config.toml} in one JVM. */
    static void clearCache() {
        CONFIG_CACHE.clear();
        SCAN_CACHE.clear();
        resolvedNerdFont = null;
    }

    // Repositories

    /**
     * Repositories declared in the {@code [repositories]} table of {@code ~/.config/jk/config.toml}.
     * Returns an empty list when the file is absent, the table is missing, or any entry is
     * malformed (lenient — global config must never fail a build).
     */
    public static List<RepositorySpec> repositories() {
        return repositories(JkDirs.userConfigFile());
    }

    /** As {@link #repositories()} but against an explicit config file — for tests. */
    static List<RepositorySpec> repositories(Path configFile) {
        return parseConfig(configFile)
                .map(toml -> parseRepositories(toml.getTable("repositories")))
                .orElse(List.of());
    }

    private static List<RepositorySpec> parseRepositories(TomlTable repos) {
        if (repos == null) return List.of();
        List<RepositorySpec> result = new ArrayList<>(repos.size());
        for (String name : repos.keySet()) {
            Object value = repos.get(name);
            String url;
            Optional<RepoCredential> credential = Optional.empty();
            Optional<ObjectStoreConfig> objectStore = Optional.empty();
            List<String> groups = List.of();
            try {
                if (value instanceof String s) {
                    url = s;
                } else if (value instanceof TomlTable t) {
                    String u = t.getString("url");
                    if (u == null) continue; // malformed — skip leniently
                    url = u;
                    credential = RepositoryToml.credential(t, LENIENT_INTERP);
                    objectStore = RepositoryToml.objectStore(t, LENIENT_INTERP);
                    try {
                        groups = RepositoryToml.groups(t, "repositories." + name);
                    } catch (RuntimeException ignored) {
                        groups = List.of(); // lenient: bad groups array skipped
                    }
                } else {
                    continue; // unexpected type — skip leniently
                }
                result.add(new RepositorySpec(name, URI.create(url), credential, objectStore, groups));
            } catch (RuntimeException ignored) {
                // malformed URL or env var — skip this entry leniently
            }
        }
        return result;
    }

    /**
     * Global-layer {@code ${ENV}} interpolation: lenient — an unset variable is left as the literal
     * {@code ${VAR}} text (global config must never fail a build). Field parsing lives in {@link
     * RepositoryToml}.
     */
    private static final UnaryOperator<String> LENIENT_INTERP = raw -> RepositoryToml.interpolate(raw, var -> {
        String v = System.getenv(var);
        return v != null ? v : "${" + var + "}";
    });
}
