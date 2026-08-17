// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.EngineTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Embedded JDK {@code jdk.httpserver}: Host-header check, admission semaphore, bearer-token gates
 * ({@link #authorized}), {@link StaticContent}, {@link ApiRouter}. Bind failure is non-fatal.
 */
public final class HttpEngineServer implements AutoCloseable {

    /** Grace period {@link HttpServer#stop} gives in-flight exchanges before hard-closing. */
    private static final int STOP_GRACE_SECONDS = 1;

    /** How often a quiet SSE stream writes a comment line — dead-client detection + proxy keepalive. */
    private static final long DEFAULT_HEARTBEAT_MILLIS = 15_000;

    /** Cap on a request body ({@code POST /api/build} carries one flat object; 64 KiB is generous). */
    static final int MAX_BODY_BYTES = 64 * 1024;

    /** Bind attempts before giving up — rides out a just-displaced predecessor still releasing the port. */
    private static final int BIND_ATTEMPTS = 25;

    /** Pause between bind attempts; {@code BIND_ATTEMPTS ×} this bounds the wait (~5s). */
    private static final long BIND_RETRY_MILLIS = 200;

    private final JkHttpConfig config;
    private final StaticContent staticContent;
    private final Semaphore admission;

    /**
     * Budgets for concurrent long-lived SSE streams — separate from RPC admission (an open stream
     * holds its slot for the connection's life, so streams drawing from the RPC semaphore would let
     * {@code maxConcurrentRequests} EventSource tabs starve every other endpoint) and from each
     * other (a runaway agent must not evict the dashboard, or vice versa).
     */
    private final Semaphore webSse;

    private final Semaphore mcpSse;
    private final Path webRoot;
    private final Path tokenFile;
    private final Path logFile;
    private final Supplier<StatusSnapshot> status;
    private final HttpEvents events;
    private final EngineHttpJobs jobs;
    private final ProgressTokenRegistry progressTokens;
    private final cc.jumpkick.engine.journal.BuildJournal journal;
    private final Supplier<java.util.List<cc.jumpkick.runtime.BuildMetrics.Entry>> metrics;
    private final Supplier<CacheSnapshot> cache;
    private final LiveVitals liveVitals;
    private final ApiRouter api = new ApiRouter();
    private final Consumer<String> log;
    private final McpHandler mcp;
    private final String engineVersion;
    private final HttpHistoryApi historyApi;
    private final HttpProjectApi projectApi;
    private final HttpReadApi readApi;

    /**
     * Currently-running jobs (from {@code EngineServer}'s in-flight registry). Used to enrich
     * {@code GET /api/history} with live {@code requestId}/progress so a hard-refreshed dashboard
     * rebinds SSE, and to drive connect-time rehydrate callbacks.
     */
    private volatile Supplier<List<HttpLive.Run>> liveRuns = List::of;

    /**
     * Invoked once after each dashboard SSE subscription is registered — delivers a compact
     * mid-flight {@code run-snapshot} (phases + progress + startedAt) to <em>that</em>
     * subscription only so a refreshed tab resumes without flooding the bus or blocking live
     * ticks behind a phase-by-phase replay.
     */
    private volatile java.util.function.Consumer<HttpEvents.Subscription> onEventsConnect = s -> {};

    private volatile HttpServer server;
    private volatile ExecutorService executor;

    /**
     * Wire the engine's live-job view. Optional — tests leave the defaults (empty / no-op).
     *
     * @param liveRuns snapshot of holds currently running
     * @param onEventsConnect after a new {@code GET /api/events} subscription is live, deliver
     *     one mid-flight snapshot per running job to that subscription only
     */
    public void setLiveRunSupport(
            Supplier<List<HttpLive.Run>> liveRuns,
            java.util.function.Consumer<HttpEvents.Subscription> onEventsConnect) {
        this.liveRuns = liveRuns != null ? liveRuns : List::of;
        this.onEventsConnect = onEventsConnect != null ? onEventsConnect : s -> {};
    }

    /** Engine hook: bump the combined-connection high-water mark on every SSE admission. */
    private volatile Runnable onSseAdmitted = () -> {};

    public void setOnSseAdmitted(Runnable onSseAdmitted) {
        this.onSseAdmitted = onSseAdmitted != null ? onSseAdmitted : () -> {};
    }

    private byte[] token;
    private long heartbeatMillis = DEFAULT_HEARTBEAT_MILLIS;

    /**
     * @param webRoot the resolved on-disk static root (the caller resolves {@code web-root} against
     * the live {@code JkDirs}; tests pass a temp dir) — need not exist
     * @param tokenFile where to persist the minted bearer token (owner-only permissions) so the CLI
     * can hand the user a tokenized URL — {@code EnginePaths.Paths#httpToken} in real use
     * @param logFile the engine's own log ({@code EnginePaths.Paths#log}), tailed by {@code
     * GET /api/log} for the dashboard's Status view
     * @param version the engine version, used for classpath-asset {@code ETag}s
     * @param status supplies the vitals {@code GET /api/status} reports, fresh per request
     * @param events the hub {@code GET /api/events} streams from ({@code EngineServer} publishes)
     * @param jobs async build/test/lock/cancel for HTTP + MCP
     * @param metrics supplies the running build aggregates {@code GET /api/metrics} reports, fresh
     * per request (the engine's {@code BuildMetrics} store)
     * @param cache supplies the cache breakdown {@code GET /api/cache} and live SSE report
     * (prefer {@link CacheSnapshot#memoizing(Path)} so concurrent REST/SSE do not re-walk)
     */
    public HttpEngineServer(
            JkHttpConfig config,
            Path webRoot,
            Path tokenFile,
            Path logFile,
            String version,
            Supplier<StatusSnapshot> status,
            HttpEvents events,
            EngineHttpJobs jobs,
            cc.jumpkick.engine.journal.BuildJournal journal,
            Supplier<java.util.List<cc.jumpkick.runtime.BuildMetrics.Entry>> metrics,
            Supplier<CacheSnapshot> cache,
            Consumer<String> log) {
        this.config = config;
        this.staticContent = new StaticContent(webRoot, version);
        this.admission = new Semaphore(config.effectiveMaxConcurrentRequests());
        this.webSse = new Semaphore(config.maxEventStreams());
        this.mcpSse = new Semaphore(config.mcp().maxEventStreams());
        this.webRoot = webRoot;
        this.tokenFile = tokenFile;
        this.logFile = logFile;
        this.status = status;
        this.events = events;
        this.jobs = jobs;
        this.journal = journal;
        this.metrics = metrics;
        this.cache = cache;
        this.liveVitals = new LiveVitals(events, status, cache);
        this.log = log != null ? log : s -> {};
        this.engineVersion = version;
        this.progressTokens = new ProgressTokenRegistry();
        // null when [mcp] enabled=false — dispatch 404s every /mcp path before reaching it.
        this.mcp = config.mcp().enabled()
                ? new McpHandler(
                        status,
                        jobs,
                        this::projectMap,
                        () -> journal.rawRecords(200),
                        version,
                        progressTokens,
                        () -> this.liveRuns.get(),
                        this::yieldingAdmission,
                        jid -> journal.rawFinishedRecordByRequestId(jid).orElse(null))
                : null;
        // jk_disk / jk_doctor / jk://disk read the same memoized walk as GET /api/cache.
        if (this.mcp != null) this.mcp.cacheSnapshot(cache);
        this.historyApi = new HttpHistoryApi(journal, () -> this.liveRuns.get());
        this.projectApi = new HttpProjectApi(journal);
        this.readApi = new HttpReadApi(config, webRoot, logFile, status, jobs, metrics, cache, this::url);
        api.register("GET", "/api/status", readApi::handleStatus);
        api.register("GET", "/api/config", readApi::handleConfig);
        api.register("GET", "/api/events", this::handleEvents);
        api.register("GET", "/api/log", readApi::handleLog);
        api.register("GET", "/api/fs", readApi::handleFs);
        api.register("POST", "/api/build", readApi::handleBuild);
        api.register("POST", "/api/cancel", readApi::handleCancel);
        api.register("GET", "/api/history", historyApi::handleHistory);
        api.register("GET", "/api/history/artifact", historyApi::handleHistoryArtifact);
        api.register("DELETE", "/api/history", historyApi::handleHistoryDelete);
        api.register("GET", "/api/metrics", readApi::handleMetrics);
        api.register("GET", "/api/cache", readApi::handleCache);
        api.register("GET", "/api/project", projectApi::handleProject);
        api.register("GET", "/api/project/graph", projectApi::handleProjectGraph);
        api.register("GET", "/api/project/files", projectApi::handleProjectFiles);
        api.register("GET", "/api/project/file", projectApi::handleProjectFile);
        api.register("PUT", "/api/project/file", projectApi::handleProjectFilePut);
        api.register("GET", "/api/project/file/raw", projectApi::handleProjectFileRaw);
        api.register("POST", "/api/projects", projectApi::handleNewProject);
        api.register("GET", "/api/projects/defaults", projectApi::handleProjectDefaults);
        api.register("GET", "/api/templates", projectApi::handleTemplates);
    }

    /**
     * Bind and start serving. Throws on an unusable {@code host} or an already-claimed port — the
     * caller treats that as "continue without HTTP", never as engine failure.
     */
    public void start() throws IOException {
        loadOrMintToken();
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
        server = bindWithRetry(bind);
        server.createContext("/", this::handle);
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-http-", 0).factory());
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Bind, retrying briefly on {@link BindException}. A just-displaced predecessor engine drains
     * gracefully (finishing in-flight work) before it releases the fixed port, so a fresh generation
     * reaching HTTP bind moments after {@code EngineServer.drainDisplaced} can still lose the handoff
     * race by a hair. A bounded retry rides that out without blocking startup for long — if the port
     * is genuinely held by something else, we give up quickly and the engine serves without HTTP,
     * exactly as before. (Port {@code 0} is OS-assigned and never collides, so this is a no-op there.)
     */
    private static HttpServer bindWithRetry(InetSocketAddress bind) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return HttpServer.create(bind, 0);
            } catch (BindException e) {
                if (attempt >= BIND_ATTEMPTS) throw e;
                try {
                    Thread.sleep(BIND_RETRY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Load the owner-only bearer token from disk, or mint one. Stable across restarts so open
     * dashboard tabs keep working; rotate only via {@code jk engine rotate-token}.
     */
    private void loadOrMintToken() throws IOException {
        String existing = readPersistedToken();
        if (existing != null) {
            token = existing.getBytes(StandardCharsets.UTF_8);
            return;
        }
        String minted = EngineTransport.newToken();
        token = minted.getBytes(StandardCharsets.UTF_8);
        Files.deleteIfExists(tokenFile);
        try {
            Files.createFile(
                    tokenFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tokenFile); // non-POSIX filesystem (Windows): default ACLs are per-user
        }
        Files.writeString(tokenFile, minted);
    }

    /** The persisted token if the file exists and holds a non-blank value, else {@code null}. */
    private String readPersistedToken() {
        try {
            if (!Files.isRegularFile(tokenFile)) return null;
            String value = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null; // unreadable — mint a fresh one rather than fail to serve
        }
    }

    /** True when the MCP surface is mounted ({@code [mcp] enabled}). */
    public boolean mcpEnabled() {
        return mcp != null;
    }

    /** The served base URL, e.g. {@code http://127.0.0.1:8910/} — actual bound port, so 0 works. */
    public String url() {
        HttpServer s = server;
        if (s == null) return null; // stopped — e.g. a lame-duck engine that released the port at handoff
        InetSocketAddress addr = s.getAddress();
        if (addr == null) return null;
        String host = addr.getAddress().isAnyLocalAddress()
                ? "127.0.0.1" // a wildcard bind is reachable via loopback; advertise the always-valid form
                : addr.getAddress().getHostAddress();
        if (host.contains(":")) host = "[" + host + "]"; // IPv6 literal
        return "http://" + host + ":" + addr.getPort() + "/";
    }

    /**
     * Stop serving and release the port immediately (grace 0), interrupting in-flight exchanges
     * including the SSE stream. Called at handoff: a displaced engine invokes this the moment it
     * becomes a lame duck so its successor can bind the fixed port without waiting on the drain.
     * Idempotent and safe alongside {@link #close}.
     */
    public synchronized void stopNow() {
        stop(0);
    }

    @Override
    public synchronized void close() {
        stop(STOP_GRACE_SECONDS);
    }

    /**
     * Change-gated {@code status} SSE after plan count may have moved (request start/finish).
     * No-op without subscribers.
     */
    public void notifyLiveStatus() {
        liveVitals.publishStatus(false);
    }

    /**
     * Change-gated {@code cache} SSE after store/action-cache may have grown (request finish, prune).
     * IO-shaped — only call off the hot step path. Invalidates a {@link CacheSnapshot.Memoizing}
     * supplier first so the async nudge walks fresh numbers rather than replaying the TTL cache.
     */
    public void notifyLiveCache() {
        if (cache instanceof CacheSnapshot.Memoizing memo) {
            memo.invalidate();
        }
        liveVitals.nudgeCache();
    }

    /** Stop the server (once) and interrupt its executor; nulling both makes any repeat call a no-op. */
    private void stop(int graceSeconds) {
        if (server != null) {
            server.stop(graceSeconds);
            server = null;
        }
        // shutdownNow, not shutdown: an SSE handler quietly parked in Subscription.next holds no
        // connection anymore after stop — the interrupt is what tells it to unsubscribe and die.
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        liveVitals.close();
    }

    /** Every request funnels through here: gates first, then dispatch. */
    private void handle(HttpExchange exchange) throws IOException {
        // The catch sits INSIDE the try-with-resources: a resource is closed before the catch of
        // the same statement runs, so a 500 written outside would always go to a closed exchange
        // and be swallowed — every handler bug read as a silent connection drop.
        try (exchange) {
            try {
                // Snapshot: stop()/stopNow() nulls `server` while exchanges are still in flight
                // (the displacement handoff does exactly that), and dereferencing it here would
                // NPE instead of closing cleanly.
                HttpServer current = server;
                if (current == null) {
                    sendText(exchange, 503, "engine is shutting down\n");
                    return;
                }
                if (!HostCheck.allowed(
                        exchange.getRequestHeaders().getFirst("Host"),
                        current.getAddress().getPort())) {
                    sendText(exchange, 421, "unrecognized Host header\n");
                    return;
                }
                boolean sse = isEventStreamRequest(exchange);
                boolean mcpSurface = isMcpPath(exchange.getRequestURI().getPath());
                Semaphore gate = sse ? (mcpSurface ? mcpSse : webSse) : admission;
                if (!gate.tryAcquire()) {
                    exchange.getResponseHeaders().set("Retry-After", "1");
                    sendText(
                            exchange,
                            503,
                            !sse
                                    ? "engine busy\n"
                                    : mcpSurface ? "too many MCP event streams\n" : "too many event streams\n");
                    return;
                }
                // Peak must be observed at admission, not when a status snapshot happens to run —
                // SSE spikes between snapshots were invisible to the high-water mark.
                if (sse) onSseAdmitted.run();
                try {
                    dispatch(exchange);
                } finally {
                    gate.release();
                }
            } catch (RuntimeException e) {
                // A handler bug must not kill the virtual thread silently mid-response.
                log.accept("jk engine: http handler error: " + e);
                try {
                    sendText(exchange, 500, "internal error\n");
                } catch (Exception ignored) {
                    // response already started (IllegalStateException) or client gone
                }
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (isMcpPath(path)) {
            if (mcp == null) {
                sendText(exchange, 404, "not found\n"); // [mcp] enabled = false
                return;
            }
            // MCP is agent-facing; always token-gated (even loopback) — same CSRF posture as POST
            // /api/build. ?access_token= exists solely for the SSE GET (EventSource cannot set
            // headers); every other shape — mutating POSTs above all — must present the Bearer
            // header, matching /api and the docs, so tokens stay out of shell history/proxy logs.
            boolean sseQueryToken = exchange.getRequestMethod().equals("GET")
                    && acceptsEventStream(exchange)
                    && tokenValid(queryParamLenient(exchange.getRequestURI().getRawQuery(), "access_token"));
            if (!tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization"))) && !sseQueryToken) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendText(exchange, 401, "missing or invalid bearer token\n");
                return;
            }
            handleMcp(exchange);
            return;
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendText(exchange, 401, "missing or invalid bearer token\n");
                return;
            }
            // Generation gate: fail-closed except bootstrap status + SSE (EventSource
            // cannot send headers). Stale dashboards hard-refresh on 409.
            if (!engineEpochOk(exchange)) {
                sendEngineEpochConflict(exchange);
                return;
            }
            api.handle(exchange);
            return;
        }
        staticContent.serve(exchange); // static is never token-gated — the dashboard shell has no secrets
    }

    /**
     * {@code GET /api/status} and {@code GET /api/events} may omit the epoch header (bootstrap /
     * EventSource). Every other {@code /api/*} call must send a matching {@code X-Jk-Engine-Epoch}.
     */
    private boolean engineEpochOk(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean bootstrap = ("GET".equals(method) || "HEAD".equals(method))
                && (path.equals("/api/status") || path.equals("/api/events"));
        if (bootstrap) return true;
        String presented = exchange.getRequestHeaders().getFirst("X-Jk-Engine-Epoch");
        if (presented == null || presented.isBlank()) return false;
        StatusSnapshot s = status.get();
        String expected = s != null ? s.engineEpoch() : null;
        return expected != null && expected.equals(presented.trim());
    }

    private void sendEngineEpochConflict(HttpExchange exchange) throws IOException {
        StatusSnapshot s = status.get();
        String epoch = s != null && s.engineEpoch() != null ? s.engineEpoch() : "";
        String body = JsonOut.object()
                .put("error", "engine-epoch-mismatch")
                .put("engineEpoch", epoch)
                .put("version", s != null ? s.version() : "")
                .put("startedAt", s != null ? s.startedAtMillis() : 0L)
                .toString();
        sendJson(exchange, 409, body);
    }

    /**
     * MCP Streamable-HTTP style: {@code POST /mcp} with JSON-RPC body; {@code GET /mcp} with {@code
     * Accept: text/event-stream} opens an SSE progress stream ({@code notifications/jk/event});
     * otherwise GET returns a small discovery document.
     */
    private void handleMcp(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET") || method.equals("HEAD")) {
            if (method.equals("GET") && acceptsEventStream(exchange)) {
                handleMcpEvents(exchange);
                return;
            }
            sendJson(
                    exchange,
                    200,
                    JsonOut.object()
                            .put("schema", 1)
                            .put("type", "mcp-discovery")
                            .put("protocolVersion", McpHandler.PROTOCOL_VERSION)
                            .put("server", McpHandler.SERVER_NAME)
                            .put("version", engineVersion)
                            .put("endpoint", "POST /mcp")
                            .put("events", "/api/events")
                            .put(
                                    "mcpEvents",
                                    "GET /mcp (Accept: text/event-stream); optional ?requestId=N or "
                                            + "?progressToken=T")
                            .put(
                                    "instructions",
                                    "JSON-RPC 2.0 POST. Methods: initialize, tools/list, tools/call, ping. "
                                            + "Bearer token required. Live progress: GET /mcp with "
                                            + "Accept: text/event-stream (optional ?requestId= or "
                                            + "?progressToken=) or GET /api/events (dashboard SSE).")
                            .toString());
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST");
            sendText(exchange, 405, "method not allowed\n");
            return;
        }
        String body = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String response = mcp.handleBody(body);
        if (response == null || response.isEmpty()) {
            // JSON-RPC notification — accepted, no body.
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        sendJson(exchange, 200, response);
    }

    /**
     * MCP progress SSE: same hub as {@code /api/events}, framed as Streamable-HTTP {@code message}
     * events with {@code notifications/jk/event} JSON-RPC bodies. Optional query filters: {@code
     * requestId} (engine job id) or {@code progressToken} (bound from tools/call {@code
     * _meta.progressToken}).
     */
    private void handleMcpEvents(HttpExchange exchange) throws IOException {
        Long filter = resolveMcpEventFilter(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (filter != null) {
            exchange.getResponseHeaders().set("X-Jk-Request-Id", Long.toString(filter));
        }
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        String hello =
                filter == null ? ": mcp-events connected\n\n" : ": mcp-events connected requestId=" + filter + "\n\n";
        try (HttpEvents.Subscription subscription = events.subscribe(HttpEvents.FrameStyle.MCP, filter)) {
            out.write(hello.getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String frame = subscription.next(heartbeatMillis);
                out.write((frame != null ? frame : ": heartbeat\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // client closed
        }
    }

    /**
     * Resolve optional SSE filter from query string. {@code requestId} wins over {@code
     * progressToken}. An unknown progress token filters to a never-matching id (no wrong-job
     * leakage); open SSE after tools/call returns, or use {@code requestId} from the tool result.
     */
    Long resolveMcpEventFilter(String query) {
        String rid = queryParamLenient(query, "requestId");
        if (rid != null && !rid.isBlank()) {
            try {
                return Long.parseLong(rid.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String tok = queryParamLenient(query, "progressToken");
        if (tok != null && !tok.isBlank()) {
            Long bound = progressTokens.resolve(tok.trim());
            // -1 never appears as a real requestId; filtered stream stays quiet until bind lands
            // on a later reconnect, or the agent switches to ?requestId=.
            return bound != null ? bound : -1L;
        }
        return null;
    }

    /**
     * Matches exactly the requests that enter a long-lived stream loop ({@link #handleEvents},
     * {@link #handleMcpEvents}) — these draw from the SSE budget, not RPC admission.
     */
    private static boolean isEventStreamRequest(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) return false;
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/events")) return true;
        return isMcpPath(path) && acceptsEventStream(exchange);
    }

    private static boolean isMcpPath(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/");
    }

    private static boolean acceptsEventStream(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept == null || accept.isBlank()) return false;
        return accept.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream");
    }

    /** Project metadata for MCP {@code jk_project} (same parse as GET /api/project). */
    private java.util.Map<String, Object> projectMap(String dir) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        Path root;
        try {
            root = cc.jumpkick.util.PathUtil.resolveUserPath(dir);
        } catch (IllegalArgumentException e) {
            m.put("dir", dir);
            return m;
        }
        m.put("dir", root.toString());
        try {
            var project = cc.jumpkick.config.JkBuildParser.parse(root.resolve("jk.toml"))
                    .project();
            m.put("coord", project.group() + ":" + project.name());
            if (project.description() != null) m.put("description", project.description());
            m.put("version", project.version());
        } catch (Exception ignored) {
            // missing/unparseable jk.toml
        }
        return m;
    }

    /**
     * Every {@code /api/*} call needs the bearer token — loopback is not a free pass. A bare
     * browser open without {@code #t=} or a stored token must not paint live activity (fail-closed).
     * Static shell assets stay ungated so the SPA can show the authorization dialog. {@code GET
     * /api/events} also accepts {@code ?access_token=} because {@code EventSource} cannot send
     * headers.
     */
    private boolean authorized(HttpExchange exchange) {
        if (tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization")))) return true;
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        return read
                && exchange.getRequestURI().getPath().equals("/api/events")
                && tokenValid(queryParamLenient(exchange.getRequestURI().getRawQuery(), "access_token"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return authorization.substring("Bearer ".length()).trim();
    }

    /**
     * The decoded value of {@code name} in a RAW query string ({@code getRawQuery()}), or null.
     * Split first, then decode each value once. Decoding never maps {@code +} to space (matches
     * the SPA's {@code encodeURIComponent}).
     */
    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return decodeOnce(pair.substring(eq + 1));
        }
        return null;
    }

    /** Percent-decode without the {@code application/x-www-form-urlencoded} {@code +}→space rule. */
    private static String decodeOnce(String raw) {
        return java.net.URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * {@link #queryParam} that treats malformed percent-encoding as an absent parameter instead of
     * throwing. For token / filter lookups where the caller's answer to garbage is "no" (401 /
     * unfiltered), not a 500 from the generic handler. Handlers that owe the client a
     * message keep the throwing form and map it to 400 themselves.
     */
    static String queryParamLenient(String rawQuery, String name) {
        try {
            return queryParam(rawQuery, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean tokenValid(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        // Constant-time, immune to length/prefix probing.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    /**
     * SSE stream: event frames plus comment heartbeats. Holds an SSE-budget slot (not an RPC
     * admission permit) for the stream's life; dead-client write and {@link #close} interrupt
     * end it.
     */
    private void handleEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        // Detached until hydrated: broadcasts don't reach the subscription while the connect
        // snapshot is captured, and the engine's rehydrate callback attaches it under its
        // connect ordering lock — so no event can fall between the snapshot and the queue
        // . If anything below throws before attach, the subscription was never in the
        // hub, so hasSubscribers() cannot stay true for the process's life.
        HttpEvents.Subscription subscription = events.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null);
        try {
            liveVitals.onSubscriberJoined();
            // Connect hydrate: deliver current vitals to THIS subscription only (change-gate
            // skipped) so the tab does not wait for the first 2s / 60s sampler tick — without
            // re-broadcasting chrome to every open tab. Cache hydrate re-sends the last
            // captured snapshot — the store walk must not delay the ": connected" write
            // . Mid-flight catch-up is one compact run-snapshot per job delivered to
            // THIS subscription only — never a broadcast phase replay (that filled the 256-frame
            // queue and froze the SPA for seconds behind live ticks).
            liveVitals.hydrateFor(subscription);
            try {
                onEventsConnect.accept(subscription);
            } catch (RuntimeException e) {
                log.accept("jk engine: sse connect rehydrate failed: " + e.getMessage());
            }
            // Safety net: the engine callback attaches inside its ordering lock; if it failed
            // (or no engine is wired, e.g. tests), attach now so live events still flow.
            events.attach(subscription);
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Batch drain: a full queue of structural+progress frames must not force one
            // write+flush per event (that stalls the socket while the CLI TUI stays smooth).
            java.util.List<String> batch = new java.util.ArrayList<>(64);
            byte[] heartbeat = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
            while (true) {
                String first = subscription.next(heartbeatMillis);
                if (first == null) {
                    out.write(heartbeat);
                    out.flush();
                    continue;
                }
                batch.clear();
                batch.add(first);
                subscription.drainTo(batch, 63);
                for (String frame : batch) {
                    out.write(frame.getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // server shutting down
        } catch (IOException e) {
            // The client closed the tab — routine stream end, not an error.
        } finally {
            subscription.close();
            liveVitals.onSubscriberLeft();
        }
    }
    /** Test seam: rebind rules for in-flight history rows. */
    HttpLive.Run matchLiveRun(java.util.Map<String, Object> rec) {
        return historyApi.matchLiveRun(rec);
    }

    /** Test seam: shrink the SSE heartbeat so quiet-stream behavior is testable in milliseconds. */
    void heartbeatMillis(long millis) {
        this.heartbeatMillis = millis;
    }

    static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Binary response with an explicit content type (image preview raw endpoint). */
    static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders()
                .set("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * {@link AdmissionYield} over the RPC gate: MCP long-polls park here after releasing their
     * permit, so 16 waiting agents cannot 503 the surface (including the {@code jk_cancel} that
     * would un-wedge them). Reacquire is uninterruptible — the balancing {@code release()} in
     * {@link #handle} must never release a permit this thread does not hold.
     */
    private <T> T yieldingAdmission(java.util.function.Supplier<T> blocking) {
        admission.release();
        try {
            return blocking.get();
        } finally {
            admission.acquireUninterruptibly();
        }
    }

    /** Test seam: the admission gate, so a saturated-server {@code 503} is deterministically testable. */
    Semaphore admission() {
        return admission;
    }

    /** Test seam: the web-UI SSE budget, so over-cap stream rejection is deterministically testable. */
    /**
     * How many long-lived SSE streams are attached right now (dashboard + MCP).
     *
     * <p>Derived from the admission budgets rather than a separate counter, so it cannot drift from what
     * actually holds a slot: a stream keeps its permit for the life of the connection.
     *
     * <p>Used to decide whether an <em>orphaned</em> engine — one no endpoint pointer names, so no CLI can
     * reach it — still has a browser attached. It deliberately has no say in the <em>displaced</em> case:
     * a successor needs this port, and a dashboard tab reconnects to it.
     */
    public int liveEventStreams() {
        int web = config.maxEventStreams() - webSse.availablePermits();
        int mcp = config.mcp().maxEventStreams() - mcpSse.availablePermits();
        return Math.max(0, web) + Math.max(0, mcp);
    }

    Semaphore webSseAdmission() {
        return webSse;
    }

    /** Test seam: the MCP SSE budget — independent of {@link #webSseAdmission()}. */
    Semaphore mcpSseAdmission() {
        return mcpSse;
    }
}
