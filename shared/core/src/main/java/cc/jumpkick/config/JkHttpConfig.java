// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Machine-scoped {@code [http]} policy for the engine's embedded server, plus the {@code [mcp]}
 * table it hosts. On by default (loopback, token-gated mutations); {@code enabled = false} /
 * {@code JK_HTTP_ENABLED=false} or a malformed config file yields empty (fail closed).
 * {@code [mcp] enabled = false} disables only the MCP surface, never the server. Not
 * project-overridable; read once at engine start.
 */
public record JkHttpConfig(
        String host, int port, int maxConcurrentRequests, int maxEventStreams, String webRoot, Mcp mcp) {

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 8910;

    /** Concurrent-request admission cap; {@code 0} = container-aware core count. */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 16;

    /** Web-UI SSE budget ({@code GET /api/events}); a separate cap from RPC admission. */
    public static final int DEFAULT_MAX_EVENT_STREAMS = 16;

    /** Relative to the resolved {@code ~/.jk} home — i.e. {@code ~/.jk/state/web} by default. */
    public static final String DEFAULT_WEB_ROOT = "state/web";

    /** The {@code [mcp]} table: surface toggle + its own SSE budget ({@code GET /mcp}). */
    public record Mcp(boolean enabled, int maxEventStreams) {
        public static final Mcp DEFAULTS = new Mcp(true, DEFAULT_MAX_EVENT_STREAMS);
    }

    public static final JkHttpConfig DEFAULTS = new JkHttpConfig(
            DEFAULT_HOST,
            DEFAULT_PORT,
            DEFAULT_MAX_CONCURRENT_REQUESTS,
            DEFAULT_MAX_EVENT_STREAMS,
            DEFAULT_WEB_ROOT,
            Mcp.DEFAULTS);

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
                EnvValues.intValue(env, "JK_HTTP_MAX_EVENT_STREAMS")
                        .filter(JkHttpConfig::validMaxEventStreams)
                        .orElse(base.maxEventStreams),
                EnvValues.string(env, "JK_HTTP_WEB_ROOT").orElse(base.webRoot),
                new Mcp(
                        EnvValues.bool(env, "JK_MCP_ENABLED").orElse(base.mcp.enabled()),
                        EnvValues.intValue(env, "JK_MCP_MAX_EVENT_STREAMS")
                                .filter(JkHttpConfig::validMaxEventStreams)
                                .orElse(base.mcp.maxEventStreams()))));
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
        if (http != null && !TomlValues.optBoolean(http, "enabled").orElse(true)) return Optional.empty();
        return Optional.of(new JkHttpConfig(
                TomlValues.optString(http, "host").orElse(DEFAULT_HOST),
                TomlValues.optInt(http, "port").filter(JkHttpConfig::validPort).orElse(DEFAULT_PORT),
                TomlValues.optInt(http, "max-concurrent-requests")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(DEFAULT_MAX_CONCURRENT_REQUESTS),
                TomlValues.optInt(http, "max-event-streams")
                        .filter(JkHttpConfig::validMaxEventStreams)
                        .orElse(DEFAULT_MAX_EVENT_STREAMS),
                TomlValues.optString(http, "web-root").orElse(DEFAULT_WEB_ROOT),
                mcpFrom(parsed.get().getTable("mcp"))));
    }

    private static Mcp mcpFrom(TomlTable mcp) {
        return new Mcp(
                TomlValues.optBoolean(mcp, "enabled").orElse(true),
                TomlValues.optInt(mcp, "max-event-streams")
                        .filter(JkHttpConfig::validMaxEventStreams)
                        .orElse(DEFAULT_MAX_EVENT_STREAMS));
    }

    private static boolean validPort(int port) {
        return port >= 0 && port <= 65535; // 0 = OS-assigned at bind, recorded in <key>.http
    }

    private static boolean validMaxConcurrentRequests(int max) {
        return max >= 0; // 0 = match the container-aware core count
    }

    private static boolean validMaxEventStreams(int max) {
        return max >= 1; // no core-count rule for stream caps; 0 would be a silent SSE blackout
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
