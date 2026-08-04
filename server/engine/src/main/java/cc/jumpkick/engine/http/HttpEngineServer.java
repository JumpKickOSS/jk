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
    private static final int MAX_BODY_BYTES = 64 * 1024;

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

    private volatile HttpServer server;
    private volatile ExecutorService executor;

    /**
     * GET paths that require the bearer token even on loopback. {@code /api/fs} lists the
     * filesystem with the owner's permissions; {@code /api/log} and {@code /api/history*} carry
     * build diagnostics with source excerpts and absolute paths; {@code /api/project} is a
     * path-existence oracle; {@code /api/metrics} emits every project dir and coordinate ever
     * built; {@code /api/projects/defaults} derives from the owner's git identity and home layout.
     */
    private static final java.util.Set<String> SENSITIVE_READS = java.util.Set.of(
            "/api/fs",
            "/api/log",
            "/api/history",
            "/api/history/artifact",
            "/api/project",
            "/api/metrics",
            "/api/projects/defaults");

    /**
     * {@code GET /api/templates} response cache — building the index walks every template root
     * (with a deep DFS for catalog-only ids), so repeated modal opens must not rescan the disk
     * (JK-1455). One immutable holder rather than two volatiles: a reader must never pair the old
     * JSON with the new timestamp and serve stale rows for a full TTL.
     */
    private record TemplatesCache(String json, long atNanos) {}

    private volatile TemplatesCache templatesCache;
    private static final long TEMPLATES_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private byte[] token;
    private long heartbeatMillis = DEFAULT_HEARTBEAT_MILLIS;

    /** {@code true} when bound beyond loopback — then even {@code /api} reads require the token. */
    private boolean readsRequireToken;

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
     * @param cache supplies the cache breakdown {@code GET /api/cache} reports, fresh per request
     * (an IO-shaped walk of the cache sections — see {@link CacheSnapshot#capture})
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
                ? new McpHandler(status, jobs, this::projectMap, () -> journal.rawRecords(200), version, progressTokens)
                : null;
        api.register("GET", "/api/status", this::handleStatus);
        api.register("GET", "/api/events", this::handleEvents);
        api.register("GET", "/api/log", this::handleLog);
        api.register("GET", "/api/fs", this::handleFs);
        api.register("POST", "/api/build", this::handleBuild);
        api.register("POST", "/api/cancel", this::handleCancel);
        api.register("GET", "/api/history", this::handleHistory);
        api.register("GET", "/api/history/artifact", this::handleHistoryArtifact);
        api.register("DELETE", "/api/history", this::handleHistoryDelete);
        api.register("GET", "/api/metrics", this::handleMetrics);
        api.register("GET", "/api/cache", this::handleCache);
        api.register("GET", "/api/project", this::handleProject);
        api.register("POST", "/api/projects", this::handleNewProject);
        api.register("GET", "/api/projects/defaults", this::handleProjectDefaults);
        api.register("GET", "/api/templates", this::handleTemplates);
    }

    /**
     * Bind and start serving. Throws on an unusable {@code host} or an already-claimed port — the
     * caller treats that as "continue without HTTP", never as engine failure.
     */
    public void start() throws IOException {
        loadOrMintToken();
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
        readsRequireToken = !bind.getAddress().isLoopbackAddress();
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
     * Change-gated {@code status} SSE after pipeline count may have moved (request start/finish).
     * No-op without subscribers.
     */
    public void notifyLiveStatus() {
        liveVitals.publishStatus(false);
    }

    /**
     * Change-gated {@code cache} SSE after store/action-cache may have grown (request finish, prune).
     * IO-shaped — only call off the hot step path.
     */
    public void notifyLiveCache() {
        liveVitals.publishCache(false);
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
        // and be swallowed — every handler bug read as a silent connection drop (JK-1476).
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
            // MCP is agent-facing; always token-gated (even loopback) — same CSRF posture as POST /api/build.
            if (!tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization")))
                    && !tokenValid(queryParam(exchange.getRequestURI().getQuery(), "access_token"))) {
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
            api.handle(exchange);
            return;
        }
        staticContent.serve(exchange); // static is never token-gated — the dashboard shell has no secrets
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
        Long filter = resolveMcpEventFilter(exchange.getRequestURI().getQuery());
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
        String rid = queryParam(query, "requestId");
        if (rid != null && !rid.isBlank()) {
            try {
                return Long.parseLong(rid.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String tok = queryParam(query, "progressToken");
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
        m.put("dir", dir);
        try {
            var project = cc.jumpkick.config.JkBuildParser.parse(Path.of(dir).resolve("jk.toml"))
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
     * Mutations always need the bearer token (CSRF defense on loopback). Reads need it when bound
     * beyond loopback; {@code GET /api/events} also accepts {@code ?access_token=} ({@code
     * EventSource} cannot send headers).
     */
    private boolean authorized(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        // Reads that disclose the engine owner's filesystem, identity, or source-bearing output are
        // never token-exempt: on a shared machine another local user must not have them for free
        // over loopback (JK-1305, JK-1453, JK-1466). Aggregate-only reads (/api/status,
        // /api/cache) and the activity stream stay open so a tokenless dashboard still works.
        String path = exchange.getRequestURI().getPath();
        boolean sensitiveRead = SENSITIVE_READS.contains(path);
        if (read && !readsRequireToken && !sensitiveRead) return true;
        if (tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization")))) return true;
        return read
                && exchange.getRequestURI().getPath().equals("/api/events")
                && tokenValid(queryParam(exchange.getRequestURI().getQuery(), "access_token"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return authorization.substring("Bearer ".length()).trim();
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    private boolean tokenValid(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        // Constant-time, immune to length/prefix probing.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        StatusSnapshot s = status.get();
        String body = JsonOut.object()
                .put("version", s.version())
                .put("pid", s.pid())
                .put("startedAt", s.startedAtMillis())
                .put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000))
                .put("activeRequests", s.activeRequests())
                .put("activePipelines", s.activePipelines())
                .put("peakActiveRequests", s.peakActiveRequests())
                .put("peakActivePipelines", s.peakActivePipelines())
                .put("heapUsedBytes", s.heapUsedBytes())
                .put("heapCommittedBytes", s.heapCommittedBytes())
                .put("heapMaxBytes", s.heapMaxBytes())
                .put("rssBytes", s.rssBytes())
                .put("aotTrainingPid", s.aotTrainingPid())
                .put("cores", s.cores())
                .put("totalMemoryBytes", s.totalMemoryBytes())
                .put("freeMemoryBytes", s.freeMemoryBytes())
                .put("systemCpuLoad", s.systemCpuLoad())
                .put("httpUrl", url())
                // url already ends with /; avoid //mcp in status/mcpUrl. Null when MCP is off.
                .put("mcpUrl", config.mcp().enabled() && url() != null ? url().replaceAll("/+$", "") + "/mcp" : null)
                .put("maxConcurrentRequests", config.effectiveMaxConcurrentRequests())
                .put("maxEventStreams", config.maxEventStreams())
                .put("mcpEnabled", config.mcp().enabled())
                .put("mcpMaxEventStreams", config.mcp().maxEventStreams())
                .put("webRoot", webRoot.toString())
                .toString();
        sendJson(exchange, 200, body);
    }

    /**
     * The tail of the engine's own log for the Status view — plain text, newest lines last.
     * Read-tier auth like every {@code /api} GET; IO-shaped (a bounded read of the file's tail).
     */
    private void handleLog(HttpExchange exchange) throws IOException {
        int requested = 120;
        String param = queryParam(exchange.getRequestURI().getQuery(), "lines");
        if (param != null) {
            try {
                requested = Math.max(1, Math.min(400, Integer.parseInt(param)));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        String tail;
        try {
            tail = tailOf(logFile, requested);
        } catch (IOException e) {
            tail = "";
        }
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] bytes = tail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Last {@code lines} lines of {@code file}, reading at most the final 256 KiB of it. */
    private static String tailOf(Path file, int lines) throws IOException {
        if (!Files.isRegularFile(file)) return "";
        long size = Files.size(file);
        long from = Math.max(0, size - 256 * 1024);
        var buf = java.nio.ByteBuffer.allocate((int) (size - from));
        try (var channel = java.nio.channels.FileChannel.open(file)) {
            channel.position(from);
            while (buf.hasRemaining() && channel.read(buf) >= 0) {}
        }
        byte[] bytes = buf.array();
        String[] all = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int end = all.length > 0 && all[all.length - 1].isEmpty() ? all.length - 1 : all.length;
        int start = Math.max(0, end - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, start, end));
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
        HttpEvents.Subscription subscription = events.subscribe();
        liveVitals.onSubscriberJoined();
        // Connect hydrate: push current vitals onto the bus (change-gate skipped) so the tab does
        // not wait for the first 2s / 30s sampler tick. Build activity remains inflicted-only.
        liveVitals.publishStatus(true);
        liveVitals.publishCache(true);
        try {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String frame = subscription.next(heartbeatMillis);
                out.write((frame != null ? frame : ": heartbeat\n\n").getBytes(StandardCharsets.UTF_8));
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

    /** Directory listings above this are truncated — a picker, not a filesystem dump. */
    private static final int MAX_FS_ENTRIES = 400;

    /**
     * {@code GET /api/fs?dir=…} — the workspace picker behind the dashboard's Browse button:
     * subdirectory names of an absolute path (default: the user's home), whether it holds a
     * {@code jk.toml}, and its parent for the up-navigation. Token-required even on loopback
     * see {@link #authorized}.
     */
    private void handleFs(HttpExchange exchange) throws IOException {
        String requested = queryParam(exchange.getRequestURI().getQuery(), "dir");
        Path dir = requested == null || requested.isBlank()
                ? Path.of(System.getProperty("user.home"))
                : Path.of(requested);
        if (!dir.isAbsolute()) {
            sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "dir must be an absolute path")
                            .toString());
            return;
        }
        dir = dir.normalize();
        java.util.List<String> subdirs = new java.util.ArrayList<>();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(".") && Files.isDirectory(entry)) subdirs.add(name);
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException e) {
            sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "not a readable directory: " + dir)
                            .toString());
            return;
        }
        subdirs.sort(String.CASE_INSENSITIVE_ORDER);
        boolean truncated = subdirs.size() > MAX_FS_ENTRIES;
        if (truncated) subdirs = subdirs.subList(0, MAX_FS_ENTRIES);
        Path parent = dir.getParent();
        sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("dir", dir.toString())
                        .put("parent", parent != null ? parent.toString() : null)
                        .put("hasJkToml", Files.isRegularFile(dir.resolve("jk.toml")))
                        .put("truncated", truncated)
                        .putStrings("dirs", subdirs)
                        .toString());
    }

    /**
     * {@code POST /api/projects} — scaffold a new project under {@code parentDir} (JK-1193). Same
     * {@link cc.jumpkick.scaffold.NewScaffolder} path as {@code jk new}. Body: name, parentDir,
     * group?, lang?, layout?, template?, executable?.
     */
    private void handleNewProject(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String name = cc.jumpkick.plugin.protocol.Jsonl.str(body, "name");
        String parentDir = cc.jumpkick.plugin.protocol.Jsonl.str(body, "parentDir");
        String group = cc.jumpkick.plugin.protocol.Jsonl.str(body, "group");
        String lang = cc.jumpkick.plugin.protocol.Jsonl.str(body, "lang");
        String layout = cc.jumpkick.plugin.protocol.Jsonl.str(body, "layout");
        String template = cc.jumpkick.plugin.protocol.Jsonl.str(body, "template");
        String framework = cc.jumpkick.plugin.protocol.Jsonl.str(body, "framework");
        boolean executable = cc.jumpkick.plugin.protocol.Jsonl.bool(body, "executable", true);
        try {
            var result = cc.jumpkick.engine.runtime.NewProjectOps.create(
                    new cc.jumpkick.engine.runtime.NewProjectOps.Request(
                            name, parentDir, group, lang, layout, template, executable, framework));
            sendJson(
                    exchange,
                    201,
                    JsonOut.object()
                            .put("path", result.path().toString())
                            .put("dir", result.path().toString())
                            .toString());
        } catch (IllegalArgumentException e) {
            sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
        } catch (IllegalStateException e) {
            sendJson(
                    exchange, 409, JsonOut.object().put("error", e.getMessage()).toString());
        } catch (IOException e) {
            sendJson(
                    exchange,
                    500,
                    JsonOut.object()
                            .put("error", e.getMessage() == null ? "scaffold failed" : e.getMessage())
                            .toString());
        }
    }

    /**
     * {@code GET /api/projects/defaults} — educated guesses for the New project modal (group from
     * git email like {@code jk new}, parent dir from history / well-known roots / git clusters).
     */
    private void handleProjectDefaults(HttpExchange exchange) throws IOException {
        String group = cc.jumpkick.scaffold.NewGroupGuess.guess();
        java.util.List<java.nio.file.Path> historyDirs = new java.util.ArrayList<>();
        try {
            for (var rec : journal.list()) {
                if (rec != null && rec.dir() != null && !rec.dir().isBlank()) {
                    historyDirs.add(java.nio.file.Path.of(rec.dir()));
                }
            }
        } catch (RuntimeException ignored) {
            // journal empty / unreadable — parent guess still works without it
        }
        java.nio.file.Path parent = cc.jumpkick.scaffold.NewParentDirGuess.guess(
                java.util.Optional.ofNullable(System.getProperty("user.home"))
                        .map(java.nio.file.Path::of)
                        .orElse(null),
                historyDirs);
        sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("group", group)
                        .put("parentDir", parent.toString())
                        .toString());
    }

    /**
     * {@code GET /api/templates} — short-name catalog for the new-project picker. Each row is
     * {@code {id, description, languages:[…], layout:"simple"|"traditional"|"custom"}}. Official
     * catalog rows are merged with on-disk {@code jk_languages}/{@code jk_layout} from local
     * template roots (see {@link cc.jumpkick.scaffold.Giter8TemplateIndex}).
     */
    private void handleTemplates(HttpExchange exchange) throws IOException {
        TemplatesCache cached = templatesCache;
        if (cached != null && System.nanoTime() - cached.atNanos() < TEMPLATES_TTL_NANOS) {
            sendJson(exchange, 200, cached.json());
            return;
        }
        // Same roots the short-name resolver uses (JK-1458) — the picker must never list a
        // template that then resolves differently, or miss one that would resolve.
        var entries = cc.jumpkick.scaffold.Giter8TemplateIndex.build(
                cc.jumpkick.scaffold.Giter8TemplateIndex.searchRoots());
        var arr = new StringBuilder("[");
        boolean first = true;
        for (var e : entries) {
            if (!first) arr.append(',');
            first = false;
            arr.append(JsonOut.object()
                    .put("id", e.id())
                    .put("description", e.description())
                    .putStrings("languages", e.languages())
                    .put("layout", e.layout())
                    .toString());
        }
        arr.append(']');
        String json = arr.toString();
        templatesCache = new TemplatesCache(json, System.nanoTime());
        sendJson(exchange, 200, json);
    }

    /** {@code POST /api/build} — acknowledge with a request id; progress streams on {@code /api/events}. */
    private void handleBuild(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String dir = cc.jumpkick.plugin.protocol.Jsonl.str(body, "dir");
        if (dir == null || dir.isBlank()) {
            sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"dir\"").toString());
            return;
        }
        long requestId;
        try {
            requestId = jobs.triggerBuild(dir);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // same fingerprint already running — 409 with human message for the UI.
            if (msg.contains("already running")) {
                sendJson(exchange, 409, JsonOut.object().put("error", msg).toString());
                return;
            }
            // Engine is draining (graceful shutdown in progress) — refuse new builds.
            exchange.getResponseHeaders().set("Retry-After", "1");
            sendJson(exchange, 503, JsonOut.object().put("error", msg).toString());
            return;
        } catch (IllegalArgumentException e) {
            sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        sendJson(
                exchange,
                202,
                JsonOut.object()
                        .put("requestId", requestId)
                        .put("jid", requestId)
                        .put("events", "/api/events")
                        .toString());
    }

    /**
     * {@code POST /api/cancel} — body {@code {"jid":N}} or {@code {"requestId":N}} (alias). Same kill
     * path as MCP {@code jk_cancel} / JSONL {@code cancel-request}.
     */
    private void handleCancel(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        long jid = cc.jumpkick.plugin.protocol.Jsonl.longValue(body, "jid", -1);
        if (jid < 0) jid = cc.jumpkick.plugin.protocol.Jsonl.longValue(body, "requestId", -1);
        if (jid < 0) {
            sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "missing \"jid\" (or requestId)")
                            .toString());
            return;
        }
        boolean ok = jobs.cancel(jid);
        sendJson(
                exchange,
                ok ? 200 : 404,
                JsonOut.object()
                        .put("jid", jid)
                        .put("requestId", jid)
                        .put("cancelled", ok)
                        .put("note", ok ? "" : "unknown or already finished jid")
                        .toString());
    }

    /** Cap on the {@code GET /api/history} list — a picker of recent runs, not a full dump. */
    private static final int HISTORY_LIST_LIMIT = 200;

    /**
     * {@code GET /api/history} — the persisted build journal (survives engine restarts). With no
     * {@code ?id=}, a JSON array of the newest entries' full records; with {@code ?id=}, that one
     * entry's {@code record.json}. Each stored record is already valid JSON, so it streams verbatim
     * (no re-serialization, and {@link JsonOut}'s flat-only shape never has to express the nested
     * arrays). Read-tier auth, like every other GET.
     */
    private void handleHistory(HttpExchange exchange) throws IOException {
        String id = decode(queryParam(exchange.getRequestURI().getQuery(), "id"));
        if (id != null && !id.isBlank()) {
            var record = journal.recordFile(id);
            if (record.isEmpty()) {
                sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "no such build: " + id).toString());
                return;
            }
            sendJson(exchange, 200, Files.readString(record.get(), StandardCharsets.UTF_8));
            return;
        }
        sendJson(exchange, 200, "[" + String.join(",", journal.rawRecords(HISTORY_LIST_LIMIT)) + "]");
    }

    /**
     * {@code GET /api/metrics[?dir=…]} — the running build aggregates as a flat JSON array, one
     * object per tier row ({@code scope}: global / project / step / project-step; avg is
     * pre-computed so the SPA stays arithmetic-free). An optional {@code dir} keeps only that
     * project's rows; the global tiers are always included. Read-tier auth, like every other GET.
     */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        String dirFilter = decode(queryParam(exchange.getRequestURI().getQuery(), "dir"));
        StringBuilder body = new StringBuilder("[");
        for (cc.jumpkick.runtime.BuildMetrics.Entry e : metrics.get()) {
            if (dirFilter != null && !e.dir().isEmpty() && !e.dir().equals(dirFilter)) continue;
            if (body.length() > 1) body.append(',');
            boolean global = e.dir().isEmpty();
            String scope = e.step() == null ? (global ? "global" : "project") : (global ? "step" : "project/step");
            body.append(JsonOut.object()
                    .put("scope", scope)
                    .put("kind", e.kind())
                    .put("dir", e.dir())
                    .put("coord", e.coord())
                    .put("step", e.step())
                    .put("okCount", e.ok().count())
                    .put("okTotalMillis", e.ok().totalMillis())
                    .put("okMinMillis", e.ok().minMillis())
                    .put("okMaxMillis", e.ok().maxMillis())
                    .put("okAvgMillis", e.ok().avgMillis())
                    .put("failCount", e.failed().count())
                    .put("failTotalMillis", e.failed().totalMillis())
                    .put("failMinMillis", e.failed().minMillis())
                    .put("failMaxMillis", e.failed().maxMillis())
                    .put("cancelledCount", e.cancelled().count())
                    .put("updated", e.updatedMillis()));
        }
        sendJson(exchange, 200, body.append(']').toString());
    }

    /**
     * {@code GET /api/cache} — the cache-directory breakdown (the {@code jk cache storage} /
     * {@code jk repo storage} sections) as one flat object, for the Status view's Cache panel. Read-tier auth, like every other GET;
     * IO-shaped (a walk of the cache sections), so it is computed per request, never cached.
     */
    private void handleCache(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, cache.get().toJson().toString());
    }

    /**
     * {@code GET /api/project?dir=…} — live workspace metadata for one project: its {@code coord}
     * ({@code group:name}) and {@code description}, parsed fresh from the dir's {@code jk.toml}. Not
     * from the journal — these describe the project as it is on disk now, so the detail page shows the
     * current description even for a project whose last build predates it. Empty object when the dir
     * has no parseable {@code jk.toml} (e.g. a deleted workspace). Read-tier auth, like every GET.
     */
    private void handleProject(HttpExchange exchange) throws IOException {
        String dir = decode(queryParam(exchange.getRequestURI().getQuery(), "dir"));
        if (dir == null || dir.isBlank()) {
            sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"dir\"").toString());
            return;
        }
        try {
            var project = cc.jumpkick.config.JkBuildParser.parse(Path.of(dir).resolve("jk.toml"))
                    .project();
            sendJson(
                    exchange,
                    200,
                    JsonOut.object()
                            .put("dir", dir)
                            .put("coord", project.group() + ":" + project.name())
                            .put("description", project.description())
                            .toString());
        } catch (RuntimeException e) {
            // Unparseable/missing jk.toml (deleted or moved workspace) → empty, never an error.
            sendJson(exchange, 200, JsonOut.object().put("dir", dir).toString());
        }
    }

    /**
     * {@code GET /api/history/artifact?id=…&name=…} — a snapshot file (test-results markdown, the
     * {@code jk-lock.toml} snapshot, or the diagnostics text) served as plain text. {@code name} is
     * whitelisted by the journal, so a hostile value cannot escape the entry directory.
     */
    private void handleHistoryArtifact(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        var artifact = journal.artifact(decode(queryParam(query, "id")), decode(queryParam(query, "name")));
        if (artifact.isEmpty()) {
            sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such artifact").toString());
            return;
        }
        sendText(exchange, 200, Files.readString(artifact.get(), StandardCharsets.UTF_8));
    }

    /**
     * {@code DELETE /api/history?id=…} — remove one entry, like deleting a CI run. DELETE is a
     * mutation, so {@link #authorized} requires the bearer token even on loopback (CSRF defense).
     */
    private void handleHistoryDelete(HttpExchange exchange) throws IOException {
        String id = decode(queryParam(exchange.getRequestURI().getQuery(), "id"));
        if (id == null || !journal.delete(id)) {
            sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such build").toString());
            return;
        }
        sendJson(exchange, 200, JsonOut.object().put("deleted", true).toString());
    }

    private static String decode(String raw) {
        return raw == null ? null : java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
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
