// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Machine-scoped {@code [http]} policy for the engine's embedded server, plus the {@code [mcp]}
 * table it hosts. On by default (loopback, token-gated mutations); {@code enabled = false} /
 * {@code JK_HTTP_ENABLED=false} or a malformed config file yields empty (fail closed).
 * {@code [mcp] enabled = false} disables only the MCP surface, never the server. Not
 * project-overridable; read once at engine start.
 *
 * <p>This reader stays on tomlj rather than {@link TomlScan}: fail-closed-on-unparseable is
 * load-bearing here, and the line scanner is tolerant by design. Per-field precedence is still
 * {@link MachineConfig}'s — one declaration of each field's default and range, applied to the file
 * layer in {@link #fromToml} and to the env layer in {@link #resolve()} alike.
 */
public record JkHttpConfig(
        String host, int port, int maxConcurrentRequests, int maxEventStreams, String webRoot, Mcp mcp) {

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 8910;

    /** Concurrent-request admission cap; {@code 0} = container-aware core count. */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 16;

    /** Web-UI SSE budget ({@code GET /api/events}); a separate cap from RPC admission. */
    public static final int DEFAULT_MAX_EVENT_STREAMS = 16;

    /** Relative to the jk home root — i.e. {@code ~/.jk/state/web} by default. */
    public static final String DEFAULT_WEB_ROOT = "state/web";

    /** {@code [mcp] tools}: the default {@code tools/list} is the fix-and-rerun loop set. */
    public static final String DEFAULT_MCP_TOOLS = "loop";

    /**
     * The {@code [mcp]} table: surface toggle, its own SSE budget ({@code GET /mcp}), and which
     * tool cards {@code tools/list} serves — {@code loop} (the default) or {@code all}.
     */
    public record Mcp(boolean enabled, int maxEventStreams, String tools) {
        public static final Mcp DEFAULTS = new Mcp(true, DEFAULT_MAX_EVENT_STREAMS, DEFAULT_MCP_TOOLS);
    }

    public static final JkHttpConfig DEFAULTS = new JkHttpConfig(
            DEFAULT_HOST,
            DEFAULT_PORT,
            DEFAULT_MAX_CONCURRENT_REQUESTS,
            DEFAULT_MAX_EVENT_STREAMS,
            DEFAULT_WEB_ROOT,
            Mcp.DEFAULTS);

    private static final MachineConfig<String> HOST = MachineConfig.of(DEFAULT_HOST);

    /** {@code 0} = OS-assigned at bind, recorded in {@code <key>.http}. */
    private static final MachineConfig<Integer> PORT = MachineConfig.of(DEFAULT_PORT, p -> p >= 0 && p <= 65535);

    /** {@code 0} = match the container-aware core count. */
    private static final MachineConfig<Integer> MAX_CONCURRENT_REQUESTS =
            MachineConfig.of(DEFAULT_MAX_CONCURRENT_REQUESTS, m -> m >= 0);

    /** No core-count rule for stream caps; {@code 0} would be a silent SSE blackout. */
    private static final MachineConfig<Integer> MAX_EVENT_STREAMS =
            MachineConfig.of(DEFAULT_MAX_EVENT_STREAMS, m -> m >= 1);

    private static final MachineConfig<String> WEB_ROOT = MachineConfig.of(DEFAULT_WEB_ROOT);

    private static final MachineConfig<Boolean> MCP_ENABLED = MachineConfig.of(Mcp.DEFAULTS.enabled());

    /** Anything but the two surfaces falls back to the loop set rather than serving nothing. */
    private static final MachineConfig<String> MCP_TOOLS =
            MachineConfig.of(DEFAULT_MCP_TOOLS, t -> "loop".equals(t) || "all".equals(t));

    /**
     * Effective machine config (env &gt; file &gt; defaults). Empty when disabled or unreadable.
     */
    public static Optional<JkHttpConfig> resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static Optional<JkHttpConfig> resolve(Path userConfig, Function<String, @Nullable String> env) {
        Optional<Boolean> envEnabled = EnvValues.bool(env, "JK_HTTP_ENABLED");
        if (envEnabled.isPresent() && !envEnabled.get()) return Optional.empty();
        Optional<JkHttpConfig> file = fromToml(userConfig);
        // Env wins over a file disable (env > user-config); the file's other keys are gone with it,
        // so a forced re-enable serves on the defaults.
        if (file.isEmpty() && !envEnabled.orElse(false)) return Optional.empty();
        JkHttpConfig base = file.orElse(DEFAULTS);
        return Optional.of(new JkHttpConfig(
                HOST.layerOver(base.host, EnvValues.string(env, "JK_HTTP_HOST").orElse(null)),
                PORT.layerOver(
                        base.port, EnvValues.intValue(env, "JK_HTTP_PORT").orElse(null)),
                MAX_CONCURRENT_REQUESTS.layerOver(
                        base.maxConcurrentRequests,
                        EnvValues.intValue(env, "JK_HTTP_MAX_CONCURRENT_REQUESTS")
                                .orElse(null)),
                MAX_EVENT_STREAMS.layerOver(
                        base.maxEventStreams,
                        EnvValues.intValue(env, "JK_HTTP_MAX_EVENT_STREAMS").orElse(null)),
                WEB_ROOT.layerOver(
                        base.webRoot, EnvValues.string(env, "JK_HTTP_WEB_ROOT").orElse(null)),
                new Mcp(
                        MCP_ENABLED.layerOver(
                                base.mcp.enabled(),
                                EnvValues.bool(env, "JK_MCP_ENABLED").orElse(null)),
                        MAX_EVENT_STREAMS.layerOver(
                                base.mcp.maxEventStreams(),
                                EnvValues.intValue(env, "JK_MCP_MAX_EVENT_STREAMS")
                                        .orElse(null)),
                        MCP_TOOLS.layerOver(
                                base.mcp.tools(),
                                EnvValues.string(env, "JK_MCP_TOOLS").orElse(null)))));
    }

    /**
     * File-only (no env): missing file/table → {@link #DEFAULTS}; {@code enabled = false} or
     * unreadable file → empty (fail closed).
     */
    public static Optional<JkHttpConfig> fromToml(Path file) {
        if (!Files.isRegularFile(file)) return Optional.of(DEFAULTS);
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return Optional.empty(); // exists but unreadable → fail closed
        TomlTable http = parsed.get().getTable("http");
        if (http != null && !TomlValues.optBoolean(http, "enabled").orElse(true)) return Optional.empty();
        return Optional.of(new JkHttpConfig(
                HOST.layer(TomlValues.optString(http, "host").orElse(null)),
                PORT.layer(TomlValues.optInt(http, "port").orElse(null)),
                MAX_CONCURRENT_REQUESTS.layer(
                        TomlValues.optInt(http, "max-concurrent-requests").orElse(null)),
                MAX_EVENT_STREAMS.layer(
                        TomlValues.optInt(http, "max-event-streams").orElse(null)),
                WEB_ROOT.layer(TomlValues.optString(http, "web-root").orElse(null)),
                mcpFrom(parsed.get().getTable("mcp"))));
    }

    private static Mcp mcpFrom(@Nullable TomlTable mcp) {
        return new Mcp(
                MCP_ENABLED.layer(TomlValues.optBoolean(mcp, "enabled").orElse(null)),
                MAX_EVENT_STREAMS.layer(
                        TomlValues.optInt(mcp, "max-event-streams").orElse(null)),
                MCP_TOOLS.layer(TomlValues.optString(mcp, "tools").orElse(null)));
    }

    /** The admission-semaphore size: the configured cap, or the container-aware core count for 0. */
    public int effectiveMaxConcurrentRequests() {
        return maxConcurrentRequests > 0
                ? maxConcurrentRequests
                : Runtime.getRuntime().availableProcessors();
    }

    /** {@code web-root} resolved against the live {@link JkDirs#home()} root when relative. */
    public Path webRootPath() {
        return webRootPath(JkDirs.home());
    }

    /** As {@link #webRootPath()} but against an explicit anchor — for tests. */
    public Path webRootPath(Path homeDir) {
        Path p = Path.of(webRoot);
        return (p.isAbsolute() ? p : homeDir.resolve(p)).normalize();
    }
}
