// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
        String host,
        int port,
        int maxConcurrentRequests,
        int maxEventStreams,
        String webRoot,
        String webOverlay,
        Mcp mcp) {

    /** A config with no {@code web-overlay}: every caller that does not exercise the overlay. */
    public JkHttpConfig(
            String host, int port, int maxConcurrentRequests, int maxEventStreams, String webRoot, Mcp mcp) {
        this(host, port, maxConcurrentRequests, maxEventStreams, webRoot, DEFAULT_WEB_OVERLAY, mcp);
    }

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 8910;

    /** Concurrent-request admission cap; {@code 0} = container-aware core count. */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 16;

    /** Web-UI SSE budget ({@code GET /api/events}); a separate cap from RPC admission. */
    public static final int DEFAULT_MAX_EVENT_STREAMS = 16;

    /**
     * {@code [http] web-root} / {@code JK_HTTP_WEB_ROOT}: unset by default, so the engine serves only
     * the dashboard shipped in its jar. A directory the engine reads is something the user named.
     */
    public static final String DEFAULT_WEB_ROOT = "";

    /** {@code [http] web-overlay}: unset by default; honoured only by a {@code -SNAPSHOT} build. */
    public static final String DEFAULT_WEB_OVERLAY = "";

    /** The version suffix that marks a development build, compared case-insensitively. */
    private static final String SNAPSHOT_SUFFIX = "-snapshot";

    /**
     * {@code [mcp] max-sessions}: MCP connections the engine keeps loaded while any is idle; past it,
     * a new {@code initialize} is refused only when every loaded one is active.
     */
    public static final int DEFAULT_MCP_MAX_SESSIONS = 256;

    /** {@code [mcp] tools}: the default {@code tools/list} is the fix-and-rerun loop set. */
    public static final String DEFAULT_MCP_TOOLS = "loop";

    /**
     * The {@code [mcp]} table's server keys: surface toggle, its own SSE budget ({@code GET /mcp}),
     * how many connections it keeps loaded, and which tool cards {@code tools/list} serves — {@code
     * loop} (the default) or {@code all}. {@code session-expiry} governs every session, so {@link
     * SessionExpiry} reads it whether or not the server is on.
     */
    public record Mcp(boolean enabled, int maxEventStreams, int maxSessions, String tools) {
        public static final Mcp DEFAULTS =
                new Mcp(true, DEFAULT_MAX_EVENT_STREAMS, DEFAULT_MCP_MAX_SESSIONS, DEFAULT_MCP_TOOLS);
    }

    public static final JkHttpConfig DEFAULTS = new JkHttpConfig(
            DEFAULT_HOST,
            DEFAULT_PORT,
            DEFAULT_MAX_CONCURRENT_REQUESTS,
            DEFAULT_MAX_EVENT_STREAMS,
            DEFAULT_WEB_ROOT,
            DEFAULT_WEB_OVERLAY,
            Mcp.DEFAULTS);

    /** The {@code [http]} table and its keys. */
    public static final UserConfigTable HTTP = UserConfigTable.of("http");

    static final UserConfigTable.Key HTTP_ENABLED_KEY = HTTP.key("enabled");
    static final UserConfigTable.Key HOST_KEY = HTTP.key("host");
    static final UserConfigTable.Key PORT_KEY = HTTP.key("port");
    static final UserConfigTable.Key MAX_CONCURRENT_REQUESTS_KEY = HTTP.key("max-concurrent-requests");
    static final UserConfigTable.Key MAX_EVENT_STREAMS_KEY = HTTP.key("max-event-streams");
    static final UserConfigTable.Key WEB_ROOT_KEY = HTTP.key("web-root");
    static final UserConfigTable.Key WEB_OVERLAY_KEY = HTTP.key("web-overlay");

    /** The {@code [mcp]} table and the server keys this reads; {@link SessionExpiry} reads its own. */
    public static final UserConfigTable MCP = UserConfigTable.of("mcp");

    static final UserConfigTable.Key MCP_ENABLED_KEY = MCP.key("enabled");
    static final UserConfigTable.Key MCP_MAX_EVENT_STREAMS_KEY = MCP.key("max-event-streams");
    static final UserConfigTable.Key MCP_MAX_SESSIONS_KEY = MCP.key("max-sessions");
    static final UserConfigTable.Key MCP_TOOLS_KEY = MCP.key("tools");

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

    /** Config file only: a process environment is no place for a setting that can replace the dashboard. */
    private static final MachineConfig<String> WEB_OVERLAY = MachineConfig.of(DEFAULT_WEB_OVERLAY);

    private static final MachineConfig<Boolean> MCP_ENABLED = MachineConfig.of(Mcp.DEFAULTS.enabled());

    private static final MachineConfig<Integer> MCP_MAX_SESSIONS =
            MachineConfig.of(DEFAULT_MCP_MAX_SESSIONS, m -> m >= 1);

    /** Anything but the two surfaces falls back to the loop set rather than serving nothing. */
    private static final MachineConfig<String> MCP_TOOLS =
            MachineConfig.of(DEFAULT_MCP_TOOLS, t -> "loop".equals(t) || "all".equals(t));

    /**
     * Effective machine config (env &gt; file &gt; defaults). Empty when disabled or unreadable.
     */
    public static Optional<JkHttpConfig> resolve() {
        return resolve(JkDirs.userConfigFile(), ProcessEnv::get);
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
                base.webOverlay,
                new Mcp(
                        MCP_ENABLED.layerOver(
                                base.mcp.enabled(),
                                EnvValues.bool(env, "JK_MCP_ENABLED").orElse(null)),
                        MAX_EVENT_STREAMS.layerOver(
                                base.mcp.maxEventStreams(),
                                EnvValues.intValue(env, "JK_MCP_MAX_EVENT_STREAMS")
                                        .orElse(null)),
                        MCP_MAX_SESSIONS.layerOver(
                                base.mcp.maxSessions(),
                                EnvValues.intValue(env, "JK_MCP_MAX_SESSIONS").orElse(null)),
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
        TomlTable http = HTTP.in(parsed.get());
        if (http != null
                && !TomlValues.optBoolean(http, HTTP_ENABLED_KEY.name()).orElse(true)) return Optional.empty();
        return Optional.of(new JkHttpConfig(
                HOST.layer(TomlValues.optString(http, HOST_KEY.name()).orElse(null)),
                PORT.layer(TomlValues.optInt(http, PORT_KEY.name()).orElse(null)),
                MAX_CONCURRENT_REQUESTS.layer(TomlValues.optInt(http, MAX_CONCURRENT_REQUESTS_KEY.name())
                        .orElse(null)),
                MAX_EVENT_STREAMS.layer(
                        TomlValues.optInt(http, MAX_EVENT_STREAMS_KEY.name()).orElse(null)),
                WEB_ROOT.layer(TomlValues.optString(http, WEB_ROOT_KEY.name()).orElse(null)),
                WEB_OVERLAY.layer(
                        TomlValues.optString(http, WEB_OVERLAY_KEY.name()).orElse(null)),
                mcpFrom(MCP.in(parsed.get()))));
    }

    private static Mcp mcpFrom(@Nullable TomlTable mcp) {
        return new Mcp(
                MCP_ENABLED.layer(
                        TomlValues.optBoolean(mcp, MCP_ENABLED_KEY.name()).orElse(null)),
                MAX_EVENT_STREAMS.layer(
                        TomlValues.optInt(mcp, MCP_MAX_EVENT_STREAMS_KEY.name()).orElse(null)),
                MCP_MAX_SESSIONS.layer(
                        TomlValues.optInt(mcp, MCP_MAX_SESSIONS_KEY.name()).orElse(null)),
                MCP_TOOLS.layer(TomlValues.optString(mcp, MCP_TOOLS_KEY.name()).orElse(null)));
    }

    /** This config bound to {@code port} instead. */
    public JkHttpConfig withPort(int port) {
        return new JkHttpConfig(host, port, maxConcurrentRequests, maxEventStreams, webRoot, webOverlay, mcp);
    }

    /**
     * Whether the user named the port ({@code [http] port} or {@code JK_HTTP_PORT}); when not, the
     * engine's role chooses it: 8910 for the host, a sticky band port for a guest.
     */
    public static boolean portNamed() {
        return portNamed(JkDirs.userConfigFile(), ProcessEnv::get);
    }

    static boolean portNamed(Path userConfig, Function<String, @Nullable String> env) {
        if (EnvValues.intValue(env, "JK_HTTP_PORT").isPresent()) return true;
        return TomlValues.parse(userConfig)
                .map(HTTP::in)
                .flatMap(http -> TomlValues.optInt(http, PORT_KEY.name()))
                .isPresent();
    }

    /** The admission-semaphore size: the configured cap, or the container-aware core count for 0. */
    public int effectiveMaxConcurrentRequests() {
        return maxConcurrentRequests > 0
                ? maxConcurrentRequests
                : Runtime.getRuntime().availableProcessors();
    }

    /**
     * A directory the engine serves dashboard files from, ahead of the jar's: {@code dir}, and whether
     * it is the development {@code overlay} rather than a {@code web-root} the user set.
     */
    public record WebDisk(Path dir, boolean overlay) {}

    /**
     * Whether {@code version} names a development build: it ends in {@code -SNAPSHOT}, in any case.
     * "Ends in" is the rule, so {@code 1.2.3-SNAPSHOT-rc1} is a release candidate, not a snapshot.
     * The caller passes the version the build stamped into the jar; nothing here reads the
     * environment or a file, because the one thing the {@code web-overlay} gate must not be is
     * something the attacker it guards against can set.
     */
    public static boolean isSnapshot(String version) {
        return version.toLowerCase(Locale.ROOT).endsWith(SNAPSHOT_SUFFIX);
    }

    /**
     * The directory dashboard files are served from before the jar's, or empty for the shipped
     * dashboard alone. Precedence: {@code web-root} (env, then file; the user named it), then
     * {@code web-overlay} when {@code version} is a snapshot, else nothing. A relative path resolves
     * against {@code homeDir}.
     */
    public Optional<WebDisk> webDisk(Path homeDir, String version) {
        if (!webRoot.isBlank()) return Optional.of(new WebDisk(resolveDir(webRoot, homeDir), false));
        if (!webOverlay.isBlank() && isSnapshot(version)) {
            return Optional.of(new WebDisk(resolveDir(webOverlay, homeDir), true));
        }
        return Optional.empty();
    }

    /**
     * Why a configured {@code web-overlay} is not in effect, as one line for the engine log and
     * {@code jk doctor}; empty when none is configured or it is honoured.
     */
    public Optional<String> webOverlayIgnored(String version) {
        if (webOverlay.isBlank()) return Optional.empty();
        if (!isSnapshot(version)) return Optional.of("web-overlay ignored: not a snapshot build");
        if (!webRoot.isBlank()) return Optional.of("web-overlay ignored: web-root is set");
        return Optional.empty();
    }

    private static Path resolveDir(String configured, Path homeDir) {
        Path p = Path.of(configured);
        return (p.isAbsolute() ? p : homeDir.resolve(p)).normalize();
    }
}
