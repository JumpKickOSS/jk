// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.runtime.ProjectCard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Embedded JDK {@code jdk.httpserver}: Host-header check, admission semaphore, {@link HttpTokenGate},
 * {@link EngineEpochGate}, {@link StaticContent}, {@link ApiRouter}. Bind failure is non-fatal.
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
    private final HttpAdmission admission;
    private final Path webRoot;
    private final HttpTokenGate tokens;
    private final EngineEpochGate epoch;
    private final Path logFile;
    private final Supplier<StatusSnapshot> status;
    private final HttpEvents events;
    private final EngineHttpJobs jobs;
    private final ProgressTokenRegistry progressTokens;
    private final BuildJournal journal;
    private final Supplier<List<BuildMetrics.Entry>> metrics;
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
    private volatile Consumer<HttpEvents.Subscription> onEventsConnect = s -> {};

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
            Supplier<List<HttpLive.Run>> liveRuns, Consumer<HttpEvents.Subscription> onEventsConnect) {
        this.liveRuns = liveRuns != null ? liveRuns : List::of;
        this.onEventsConnect = onEventsConnect != null ? onEventsConnect : s -> {};
    }

    /** Engine hook: bump the combined-connection high-water mark on every SSE admission. */
    private volatile Runnable onSseAdmitted = () -> {};

    /** Engine hook: the cache maintenance gate for MCP {@code jk_disk clean|nuke}. */
    public void setCacheGate(ReentrantReadWriteLock cacheGate) {
        if (mcp != null && cacheGate != null) mcp.cacheGate(cacheGate);
    }

    public void setOnSseAdmitted(Runnable onSseAdmitted) {
        this.onSseAdmitted = onSseAdmitted != null ? onSseAdmitted : () -> {};
    }

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
            BuildJournal journal,
            Supplier<List<BuildMetrics.Entry>> metrics,
            Supplier<CacheSnapshot> cache,
            Consumer<String> log) {
        this.config = config;
        this.staticContent = new StaticContent(webRoot, version);
        this.admission = new HttpAdmission(config);
        this.webRoot = webRoot;
        this.tokens = new HttpTokenGate(tokenFile);
        this.epoch = new EngineEpochGate(status);
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
        // MCP journal reads are redacted at the supplier — every consumer (history view=full,
        // diagnostics, project cards, run-wait summaries) sees the same defense-in-depth as REST.
        this.mcp = config.mcp().enabled()
                ? new McpHandler(
                        status,
                        jobs,
                        this::projectMap,
                        () -> HttpHistoryApi.redactRecords(journal.rawRecords(200)),
                        version,
                        progressTokens,
                        () -> this.liveRuns.get(),
                        admission::yieldingRpc,
                        jid -> journal.rawFinishedRecordByRequestId(jid)
                                .map(r -> HttpHistoryApi.redactRecordJson(r, new HashMap<>()))
                                .orElse(null))
                : null;
        // jk_disk / jk_doctor / jk://disk read the same memoized walk as GET /api/cache.
        if (this.mcp != null) this.mcp.cacheSnapshot(cache);
        // jk_details serves a budgeted tail of the journal-owned details.jsonl transcript.
        if (this.mcp != null) this.mcp.detailsFile(journal::detailsFile);
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
     *
     * <p>Default host is IPv4 loopback. The process must have {@code java.net.preferIPv4Stack=true}
     * ({@link cc.jumpkick.host.PreferIpv4}) so the listener is a real AF_INET socket — required for
     * WSL2 localhost forwarding from Windows.
     */
    public void start() throws IOException {
        tokens.loadOrMint();
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
        server = bindWithRetry(bind);
        server.createContext("/", this::handle);
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-http-", 0).factory());
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Bind, retrying briefly on {@link BindException}. The predecessor yields HTTP before sending
     * {@code bye}, and {@code EngineElection.askPredecessorToYield} waits for that line, so the common path binds first
     * try. A bounded retry still covers a hair-trigger race or an unrelated occupant; if the port
     * is genuinely held, we give up quickly and serve without HTTP. (Port {@code 0} is OS-assigned
     * and never collides, so this is a no-op there.)
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
                    HttpResponses.sendText(exchange, 503, "engine is shutting down\n");
                    return;
                }
                if (!HostCheck.allowed(
                        exchange.getRequestHeaders().getFirst("Host"),
                        current.getAddress().getPort())) {
                    HttpResponses.sendText(exchange, 421, "unrecognized Host header\n");
                    return;
                }
                HttpAdmission.Lane lane = admission.laneFor(exchange);
                if (!lane.tryAcquire()) {
                    exchange.getResponseHeaders().set("Retry-After", "1");
                    HttpResponses.sendText(exchange, 503, lane.busy());
                    return;
                }
                // Peak must be observed at admission, not when a status snapshot happens to run —
                // SSE spikes between snapshots were invisible to the high-water mark.
                if (lane.stream()) onSseAdmitted.run();
                try {
                    dispatch(exchange);
                } finally {
                    lane.release();
                }
            } catch (RuntimeException e) {
                // A handler bug must not kill the virtual thread silently mid-response.
                log.accept("jk engine: http handler error: " + e);
                try {
                    HttpResponses.sendText(exchange, 500, "internal error\n");
                } catch (Exception ignored) {
                    // response already started (IllegalStateException) or client gone
                }
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (HttpAdmission.isMcpPath(path)) {
            if (mcp == null) {
                HttpResponses.sendText(exchange, 404, "not found\n"); // [mcp] enabled = false
                return;
            }
            if (!tokens.authorizesMcp(exchange)) {
                tokens.challenge(exchange);
                return;
            }
            handleMcp(exchange);
            return;
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            if (!tokens.authorizesApi(exchange)) {
                tokens.challenge(exchange);
                return;
            }
            if (!epoch.allows(exchange)) {
                epoch.sendConflict(exchange);
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
            if (method.equals("GET") && HttpAdmission.acceptsEventStream(exchange)) {
                handleMcpEvents(exchange);
                return;
            }
            HttpResponses.sendJson(
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
                                    "GET /mcp (Accept: text/event-stream); optional ?jid=N or " + "?progressToken=T")
                            .put(
                                    "instructions",
                                    "JSON-RPC 2.0 POST. Methods: initialize, tools/list, tools/call, ping. "
                                            + "Bearer token required. Live progress: GET /mcp with "
                                            + "Accept: text/event-stream (optional ?jid= or "
                                            + "?progressToken=) or GET /api/events (dashboard SSE).")
                            .toString());
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST");
            HttpResponses.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        String body = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String response = mcp.handleBody(body);
        if (response == null || response.isEmpty()) {
            // JSON-RPC notification — accepted, no body.
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        HttpResponses.sendJson(exchange, 200, response);
    }

    /**
     * MCP progress SSE: same hub as {@code /api/events}, framed as Streamable-HTTP {@code message}
     * events with {@code notifications/jk/event} JSON-RPC bodies. Optional query filters: {@code
     * jid} (engine job id) or {@code progressToken} (bound from tools/call {@code
     * _meta.progressToken}).
     */
    private void handleMcpEvents(HttpExchange exchange) throws IOException {
        Long filter = resolveMcpEventFilter(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (filter != null) {
            exchange.getResponseHeaders().set("X-Jk-Jid", Long.toString(filter));
        }
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        String hello = filter == null ? ": mcp-events connected\n\n" : ": mcp-events connected jid=" + filter + "\n\n";
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
     * Resolve optional SSE filter from query string. {@code jid} wins over {@code
     * progressToken}. An unknown progress token filters to a never-matching id (no wrong-job
     * leakage); open SSE after tools/call returns, or use {@code requestId} from the tool result.
     */
    Long resolveMcpEventFilter(String query) {
        String rid = HttpQuery.queryParamLenient(query, "jid");
        if (rid != null && !rid.isBlank()) {
            try {
                return Long.parseLong(rid.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String tok = HttpQuery.queryParamLenient(query, "progressToken");
        if (tok != null && !tok.isBlank()) {
            Long bound = progressTokens.resolve(tok.trim());
            // -1 never appears as a real requestId; filtered stream stays quiet until bind lands
            // on a later reconnect, or the agent switches to ?requestId=.
            return bound != null ? bound : -1L;
        }
        return null;
    }

    /** Project metadata fallback for MCP {@code jk_project} — one card, one parse path. */
    private Map<String, Object> projectMap(String dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        Path root;
        try {
            root = PathUtil.resolveUserPath(dir);
        } catch (IllegalArgumentException e) {
            m.put("dir", dir);
            return m;
        }
        ProjectCard card = ProjectCard.of(root);
        m.put("dir", card.dir());
        if (card.coord() != null) m.put("coord", card.coord());
        if (card.description() != null) m.put("description", card.description());
        if (card.version() != null) m.put("version", card.version());
        return m;
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
        // connect ordering lock — so no event can fall between the snapshot and the queue.
        // If anything below throws before attach, the subscription was never in the
        // hub, so hasSubscribers() cannot stay true for the process's life.
        HttpEvents.Subscription subscription = events.subscribeDetached(HttpEvents.FrameStyle.DASHBOARD, null);
        try {
            liveVitals.onSubscriberJoined();
            // Connect hydrate: deliver current vitals to THIS subscription only (change-gate
            // skipped) so the tab does not wait for the first 2s / 60s sampler tick — without
            // re-broadcasting chrome to every open tab. Cache hydrate re-sends the last
            // captured snapshot — the store walk must not delay the ": connected" write.
            // Mid-flight catch-up is one compact run-snapshot per job delivered to
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
            List<String> batch = new ArrayList<>(64);
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
    HttpLive.Run matchLiveRun(Map<String, Object> rec) {
        return historyApi.matchLiveRun(rec);
    }

    /** Test seam: shrink the SSE heartbeat so quiet-stream behavior is testable in milliseconds. */
    void heartbeatMillis(long millis) {
        this.heartbeatMillis = millis;
    }

    /** How many long-lived SSE streams are attached right now (dashboard + MCP); see {@link HttpAdmission}. */
    public int liveEventStreams() {
        return admission.liveEventStreams();
    }

    /** Test seam: the three admission budgets. */
    HttpAdmission admission() {
        return admission;
    }
}
