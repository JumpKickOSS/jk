// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.http.mcp.McpConnection;
import cc.jumpkick.engine.http.mcp.McpConnections;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpElicitModes;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpOutbound;
import cc.jumpkick.engine.http.mcp.McpPrompts;
import cc.jumpkick.engine.http.mcp.McpResources;
import cc.jumpkick.engine.http.mcp.McpRpc;
import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.http.mcp.NewProjectWizard;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.mcp.McpRevision;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Thin MCP (Model Context Protocol) JSON-RPC surface for agents. Hosted on the engine
 * HTTP server next to the web dashboard — not a second build engine.
 *
 * <p>Transport: {@code POST /mcp} with a JSON-RPC 2.0 body (single object or batch array). Responses
 * are {@code application/json}. Same bearer-token policy as other mutating engine HTTP endpoints.
 * {@code initialize} mints an {@code Mcp-Session-Id}; a client that echoes it on later calls is one
 * {@link McpConnection}, and the jobs it starts journal under that session.
 *
 * <p>Tools project the same facts as CLI JSONL / {@code /api/*} (status, build trigger, project
 * metadata, history). Live progress: {@code GET /mcp} with {@code Accept: text/event-stream}
 * (MCP {@code notifications/jk/event}); filter with {@code ?jid=} or {@code
 * ?progressToken=} (bound from tools/call {@code _meta.progressToken}). See {@code
 * docs/user/machine-output.md}.
 *
 * <p>This class is the method table and nothing else. Framing is {@link McpRpc}, the tool set is
 * {@link McpTools#standard()}, and every collaborator a tool reaches hangs off {@link McpContext}.
 * {@code tools/list} answers {@link McpTools#LOOP} by default. {@code extended: true} on that
 * call, or {@link #surface}, widens it to every tool.
 */
public final class McpHandler {

    public static final String SERVER_NAME = "jk-engine";

    /** The session origin every MCP connection's runs and actions carry. */
    private static final String ORIGIN = "mcp";

    private final McpContext ctx;
    private volatile McpTools tools = McpTools.standard();

    /** Which rows {@code tools/list} answers; the loop set unless the machine config opted into all. */
    private volatile McpTools.Surface surface = McpTools.Surface.LOOP;

    /** The minimum wiring: no progress tokens, no live-run feed, no admission yield. */
    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version) {
        this(status, jobs, projectLookup, historyRaw, version, null, null, null, null);
    }

    /** Full wiring, as the embedded server builds it. Any optional collaborator may be null. */
    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            @Nullable String version,
            @Nullable ProgressTokenRegistry progressTokens,
            @Nullable Supplier<List<HttpLive.Run>> liveRuns,
            @Nullable AdmissionYield admissionYield,
            @Nullable LongFunction<String> finishedRecords) {
        this.ctx = new McpContext(
                status,
                jobs,
                projectLookup,
                historyRaw,
                version,
                progressTokens,
                liveRuns,
                admissionYield,
                finishedRecords);
    }

    /** Wire the journal's {@code details.jsonl} locator for {@code details}. Optional. */
    public void detailsFile(Function<String, Optional<Path>> resolver) {
        if (resolver != null) ctx.detailsFile(resolver);
    }

    /** Wire the dashboard's new-project wizard, which MCP {@code new} opens by URL elicitation. Optional. */
    public void wizard(NewProjectWizard wizard) {
        ctx.wizard(wizard);
    }

    /** Wire the engine's decision queue and the dashboard links into it. Optional. */
    public void decisions(Decisions decisions) {
        ctx.decisions(decisions);
    }

    /** Wire the shared cache/store snapshot supplier (memoized in the live engine). Optional. */
    public void cacheSnapshot(Supplier<CacheSnapshot> cacheSnapshot) {
        ctx.cacheSnapshot(cacheSnapshot);
    }

    /** Wire the engine's cache maintenance gate so destructive disk tools take the real locks. */
    public void cacheGate(ReentrantReadWriteLock cacheGate) {
        ctx.cacheGate(cacheGate);
    }

    /** Wire the engine's one-jid liveness probe, what a parked {@code run} wait polls. */
    public void liveJid(LongPredicate liveJid) {
        ctx.liveJid(liveJid);
    }

    /** Serve the whole registry on {@code tools/list} ({@code [mcp] tools = "all"}) instead of the loop set. */
    public void surface(McpTools.Surface surface) {
        this.surface = surface;
    }

    /** Keep at most {@code max} connections loaded while any is idle ({@code [mcp] max-sessions}). */
    public void maxSessions(int max) {
        ctx.connections().max(max);
    }

    /** The connection table's limit and how many connections are loaded and active. */
    public McpConnections.Counts connectionCounts() {
        return ctx.connections().counts();
    }

    /** Serve another tool registry; tests only. */
    void tools(McpTools tools) {
        this.tools = tools;
    }

    /** Shrink the journal-write settle budget; tests only. */
    public void journalSettleMs(long ms) {
        ctx.journalSettleMs(ms);
    }

    /** Shrink one second of a wait's {@code timeout_s}; tests only. */
    void waitSecondMs(long ms) {
        ctx.waitSecondMs(ms);
    }

    /** The live connection behind {@code sessionId}, or null; test seam. */
    @Nullable
    McpConnection connection(@Nullable String sessionId) {
        return ctx.connections().find(sessionId);
    }

    /**
     * Log connections' sessions and their non-run actions in {@code sessions}, and keep each
     * connection with its session, so an engine that adopts the session answers its id and a
     * session that ends takes its connection with it. Optional.
     */
    public void sessions(SessionLog sessions) {
        ctx.sessions(sessions);
        McpConnections connections = ctx.connections();
        sessions.whenConnectionEnds(connections::close);
        ctx.connections().store(new McpConnections.Store() {
            @Override
            public void save(McpConnection connection) {
                sessions.saveConnection(ORIGIN, connection.label(), connection.toMap());
            }

            @Override
            public @Nullable McpConnection load(String id) {
                return sessions.connection(id).map(McpConnection::of).orElse(null);
            }

            @Override
            public boolean holds(String id) {
                return sessions.holdsConnection(id);
            }
        });
    }

    /** Forget a connection ({@code DELETE /mcp}); its session ends. */
    public boolean closeConnection(@Nullable String sessionId) {
        McpConnection c = ctx.connections().find(sessionId);
        boolean closed = ctx.connections().close(sessionId);
        SessionLog log = ctx.sessions();
        if (closed && c != null && log != null) log.end(ORIGIN, c.label());
        return closed;
    }

    /**
     * One HTTP body from an anonymous connection. Returns the JSON response body (object or array);
     * notifications ({@code id} absent) yield an empty string — caller should respond 202 with no
     * body or {@code {}}.
     */
    public String handleBody(String body) {
        return handle(body, null).body();
    }

    /**
     * The response body, the HTTP status it goes out with, and, when this body's {@code initialize}
     * opened a connection, the {@code Mcp-Session-Id} the transport must hand back. {@code
     * retryAfterSeconds} is the {@code Retry-After} of a {@code 503}, else 0.
     */
    public record Reply(String body, @Nullable String openedSessionId, int status, long retryAfterSeconds) {}

    /** The JSON-RPC code of a request naming a session this engine does not know. */
    static final int UNKNOWN_SESSION = -32001;

    /** The JSON-RPC code of a request the transport refuses as it stands. */
    static final int BAD_REQUEST = -32000;

    /**
     * Handle one HTTP body (JSON-RPC request or batch) from the connection behind {@code sessionId},
     * or from no connection when it is null.
     */
    public Reply handle(String body, @Nullable String sessionId) {
        return handle(body, sessionId, null, null, null);
    }

    /** As {@link #handle(String, String)} for a client that names its project, with no way back to it. */
    public Reply handle(String body, @Nullable String sessionId, @Nullable String project) {
        return handle(body, sessionId, project, null, null);
    }

    /**
     * One HTTP body. {@code sessionId} names a connection this engine has loaded, or one stored
     * with a session it adopted, which is loaded and answered as it was; an id found nowhere is a
     * {@code 404}, and the client starts a new session with {@code initialize}. {@code project} is
     * the {@code Jk-Project} header ({@code jk mcp} sends it): {@code initialize} opens a connection
     * pinned to it, and any other request from such a client without a session is a {@code 400}.
     * {@code protocolVersion} is the {@code MCP-Protocol-Version} header; one jk does not speak is
     * an {@link IllegalArgumentException}. {@code outbound} is the way back to the client while
     * this body's request is outstanding, or null when the transport has none.
     */
    public Reply handle(
            String body,
            @Nullable String sessionId,
            @Nullable String project,
            @Nullable String protocolVersion,
            @Nullable McpOutbound outbound) {
        McpRevision named = protocolVersion == null || protocolVersion.isBlank()
                ? null
                : McpRevision.parse(protocolVersion)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "unsupported MCP-Protocol-Version: " + protocolVersion.strip()));
        String pin = project == null || project.isBlank() ? null : pinnedDir(project);
        String claimed = sessionId == null || sessionId.isBlank() ? null : sessionId.strip();
        McpConnection connection = ctx.connections().find(sessionId);
        McpRevision revision =
                named != null ? named : connection != null ? connection.revision() : McpRevision.HTTP_DEFAULT;
        if (claimed != null && connection == null) {
            return refuse(
                    body,
                    404,
                    UNKNOWN_SESSION,
                    "unknown Mcp-Session-Id " + claimed + ": send initialize to start a new session",
                    revision);
        }
        AtomicReference<McpConnection> opened = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(200);
        AtomicLong retryAfter = new AtomicLong();
        if (connection != null) connection.callStarted();
        String response;
        try {
            response = McpRpc.handleBody(
                    body,
                    new McpRpc.Router() {
                        @Override
                        public @Nullable Object call(String method, Map<String, Object> params) {
                            if ("initialize".equals(method)) {
                                McpConnection c;
                                try {
                                    c = initialize(params, pin);
                                } catch (McpConnections.Full full) {
                                    status.set(503);
                                    retryAfter.set(full.retryAfterSeconds());
                                    throw new McpError(BAD_REQUEST, full.getMessage());
                                }
                                opened.set(c);
                                String playbook = c.pinned() ? McpTools.PINNED_INSTRUCTIONS : McpTools.INSTRUCTIONS;
                                return McpRpc.initialize(c.revision().wire(), SERVER_NAME, ctx.version(), playbook);
                            }
                            if (connection == null && pin != null) {
                                status.set(400);
                                throw new McpError(BAD_REQUEST, "no Mcp-Session-Id: send initialize first");
                            }
                            return route(method, params, connection, outbound);
                        }

                        @Override
                        public void answer(Map<String, Object> response) {
                            if (connection != null) connection.answer(response);
                        }
                    },
                    revision.batches());
        } finally {
            if (connection != null) connection.callEnded();
        }
        McpConnection c = opened.get();
        return new Reply(response, c == null ? null : c.id(), status.get(), retryAfter.get());
    }

    /** True when {@code sessionId} is absent or names a connection this engine can answer. */
    public boolean knows(@Nullable String sessionId) {
        return sessionId == null || sessionId.isBlank() || ctx.connections().find(sessionId) != null;
    }

    /** Answer every request in {@code body} with one JSON-RPC error, under {@code status}; nothing runs. */
    private static Reply refuse(String body, int status, int code, String message, McpRevision revision) {
        String response = McpRpc.handleBody(
                body,
                (method, params) -> {
                    throw new McpError(code, message);
                },
                revision.batches());
        return new Reply(response, null, status, 0);
    }

    /**
     * Open the connection an {@code initialize} asks for (its revision and its elicitation modes)
     * with its session, and store the connection with the session. The one place a connection
     * opens, so every session has a connection that can end it.
     */
    private McpConnection initialize(Map<String, Object> params, @Nullable String pin) {
        McpRevision revision = McpRevision.negotiate(params.get("protocolVersion"));
        McpElicitModes modes = McpElicitModes.declared(params.get("capabilities"), revision);
        McpConnection c = ctx.connections().open(clientName(params), pin, revision, modes);
        SessionLog log = ctx.sessions();
        if (log != null) {
            log.open(ORIGIN, c.client(), c.label(), c.dir());
            ctx.connections().save(c);
        }
        return c;
    }

    /** The absolute project dir a {@code Jk-Project} header names. */
    private static String pinnedDir(String project) {
        try {
            return McpHistoryViews.dirKey(project);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid Jk-Project: " + e.getMessage(), e);
        }
    }

    /** {@code params.clientInfo.name} of an {@code initialize}, or null when the client sent none. */
    private static @Nullable String clientName(Map<String, Object> params) {
        if (!(params.get("clientInfo") instanceof Map<?, ?> info)) return null;
        Object name = info.get("name");
        return name == null ? null : String.valueOf(name);
    }

    /**
     * The whole method table. Every arm here must appear in {@link McpRpc#initialize}'s
     * capabilities, and every capability there must have an arm here; {@code initialize} itself is
     * answered in {@link #handle}, where the connection is minted.
     */
    private @Nullable Object route(
            String method,
            Map<String, Object> params,
            @Nullable McpConnection connection,
            @Nullable McpOutbound outbound) {
        return switch (method) {
            case "notifications/initialized", "initialized" -> null; // notification
            case "notifications/cancelled" -> {
                McpJobRuns.cancelled(ctx, params);
                yield null;
            }
            case "ping" -> Map.of();
            case "tools/list" -> tools.listing(surface, params, connection != null && connection.pinned());
            case "tools/call" -> tools.call(ctx, params, connection, outbound);
            case "resources/list" -> McpResources.list();
            case "resources/read" -> McpResources.read(ctx, params, connection);
            case "prompts/list" -> McpPrompts.list();
            case "prompts/get" -> McpPrompts.get(params);
            case "logging/setLevel" -> Map.of(); // declared capability; engine log level is fixed
            default -> throw new McpError(-32601, "method not found: " + method);
        };
    }
}
