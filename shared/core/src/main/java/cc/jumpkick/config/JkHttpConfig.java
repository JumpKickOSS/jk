// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Machine-scoped {@code [http]} policy for the engine's embedded server. On by default
 * (loopback, token-gated mutations); {@code enabled = false} / {@code JK_HTTP_ENABLED=false}
 * or a malformed config file yields empty (fail closed). Not project-overridable; read once at
 * engine start.
 */
public record JkHttpConfig(String host, int port, int maxConcurrentRequests, String webRoot) {

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 8910;

    /** Concurrent-request admission cap; {@code 0} = container-aware core count. */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 16;

    /** Relative to the resolved {@code ~/.jk} home — i.e. {@code ~/.jk/state/web} by default. */
    public static final String DEFAULT_WEB_ROOT = "state/web";

    public static final JkHttpConfig DEFAULTS =
            new JkHttpConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_WEB_ROOT);

    /**
     * Effective machine config (env &gt; file &gt; defaults). Empty when disabled or unreadable.
     */
    public static Optional<JkHttpConfig> resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static Optional<JkHttpConfig> resolve(Path userConfig, Function<String, String> env) {
        Optional<Boolean> envEnabled = EnvValues.bool(env, "JK_HTTP_ENABLED");
        if (envEnabled.isPresent() && !envEnabled.get()) return Optional.empty();
        Optional<JkHttpConfig> file = fromToml(userConfig);
        // Env wins over a file disable (env > user-config); the file's other keys are gone with it,
        // so a forced re-enable serves on the defaults.
        if (file.isEmpty() && !envEnabled.orElse(false)) return Optional.empty();
        JkHttpConfig base = file.orElse(DEFAULTS);
        return Optional.of(new JkHttpConfig(
                EnvValues.string(env, "JK_HTTP_HOST").orElse(base.host),
                EnvValues.intValue(env, "JK_HTTP_PORT")
                        .filter(JkHttpConfig::validPort)
                        .orElse(base.port),
                EnvValues.intValue(env, "JK_HTTP_MAX_CONCURRENT_REQUESTS")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(base.maxConcurrentRequests),
                EnvValues.string(env, "JK_HTTP_WEB_ROOT").orElse(base.webRoot)));
    }

    /**
     * File-only (no env): missing file/table → {@link #DEFAULTS}; {@code enabled = false} or
     * unreadable file → empty (fail closed).
     */
    public static Optional<JkHttpConfig> fromToml(Path file) {
        if (!java.nio.file.Files.isRegularFile(file)) return Optional.of(DEFAULTS);
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return Optional.empty(); // exists but unreadable → fail closed
        TomlTable http = parsed.get().getTable("http");
        if (http == null) return Optional.of(DEFAULTS);
        if (!TomlValues.optBoolean(http, "enabled").orElse(true)) return Optional.empty();
        return Optional.of(new JkHttpConfig(
                TomlValues.optString(http, "host").orElse(DEFAULT_HOST),
                TomlValues.optInt(http, "port").filter(JkHttpConfig::validPort).orElse(DEFAULT_PORT),
                TomlValues.optInt(http, "max-concurrent-requests")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(DEFAULT_MAX_CONCURRENT_REQUESTS),
                TomlValues.optString(http, "web-root").orElse(DEFAULT_WEB_ROOT)));
    }

    private static boolean validPort(int port) {
        return port >= 0 && port <= 65535; // 0 = OS-assigned at bind, recorded in <key>.http
    }

    private static boolean validMaxConcurrentRequests(int max) {
        return max >= 0; // 0 = match the container-aware core count
    }

    /** The admission-semaphore size: the configured cap, or the container-aware core count for 0. */
    public int effectiveMaxConcurrentRequests() {
        return maxConcurrentRequests > 0
                ? maxConcurrentRequests
                : Runtime.getRuntime().availableProcessors();
    }

    /** {@code web-root} resolved against the live {@link JkDirs#home()} when relative. */
    public Path webRootPath() {
        return webRootPath(JkDirs.home());
    }

    /** As {@link #webRootPath()} but against an explicit home dir — for tests. */
    public Path webRootPath(Path homeDir) {
        Path p = Path.of(webRoot);
        return (p.isAbsolute() ? p : homeDir.resolve(p)).normalize();
    }
}
