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
 * Machine-scoped preferences from {@code ~/.config/jk/config.toml}: {@code [global]} UI flags (e.g.
 * {@code nerdfont}) and global {@code [repositories]}. Not project-overridable; env overrides
 * apply. Project {@code [repositories]} win on name collision; global fills gaps.
 */
public final class GlobalConfig {

    private GlobalConfig() {}

    /**
     * Whether Nerd Font glyphs may be used. Precedence: env {@code JK_NERDFONT} → {@code
     * ~/.config/jk/config.toml} {@code [global].nerdfont} → default {@code false} (safer than PUA tofu;
     * set via {@code jk self setup-terminal} / install —. Forced false when color is
     * disabled.
     */
    public static boolean nerdfont() {
        return nerdfont(JkDirs.userConfigFile(), System.getenv("JK_NERDFONT"), colorActivelyEnabled());
    }

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

    /** As {@link #nerdfont()} but against an explicit config file — for tests. */
    static boolean nerdfont(Path configFile) {
        return nerdfont(configFile, System.getenv("JK_NERDFONT"), colorActivelyEnabled());
    }

    /** As {@link #nerdfont(Path)} but with an explicit env value — bypasses color check for tests. */
    static boolean nerdfont(Path configFile, String envValue) {
        return EnvValues.parseBool(envValue).orElseGet(() -> booleanFromGlobal(configFile, "nerdfont", false));
    }

    /** Full testable overload: config file + env value + explicit color-enabled flag. */
    static boolean nerdfont(Path configFile, String envValue, boolean colorEnabled) {
        if (!colorEnabled) return false;
        return EnvValues.parseBool(envValue).orElseGet(() -> booleanFromGlobal(configFile, "nerdfont", false));
    }

    /** Lenient {@code [global]} boolean via {@link TomlScan}; memoized per path+size+mtime. */
    private static boolean booleanFromGlobal(Path file, String key, boolean fallback) {
        if (file == null) return fallback;
        String cacheKey;
        long size;
        long modified;
        try {
            if (!Files.exists(file)) return fallback;
            var attrs = Files.readAttributes(file, BasicFileAttributes.class);
            cacheKey = file + "|global." + key;
            size = attrs.size();
            modified = attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return fallback;
        }
        String value = memoized(
                        SCAN_CACHE,
                        cacheKey,
                        size,
                        modified,
                        () -> Optional.ofNullable(
                                TomlScan.scan(file, "global." + key).get("global." + key)))
                .orElse(null);
        if (value == null) return fallback;
        return "true".equalsIgnoreCase(value) ? true : "false".equalsIgnoreCase(value) ? false : fallback;
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

    /** Read a single string value from an arbitrary {@code [table].key}, leniently, via TomlScan. */
    private static Optional<String> stringFromGlobal(Path file, String table, String key) {
        if (file == null) return Optional.empty();
        String dotted = table + "." + key;
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
     * so it grew for the life of the process (JK-1483).
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
