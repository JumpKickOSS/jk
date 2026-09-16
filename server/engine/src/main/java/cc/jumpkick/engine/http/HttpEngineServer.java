// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.host.Log;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.ProjectIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Embedded JDK {@code jdk.httpserver}: bind/stop lifecycle, the gate chain every exchange passes
 * (Host header, {@link HttpAdmission}, {@link HttpTokenGate}, {@link EngineEpochGate}), the route
 * table over {@link ApiRouter} / {@link McpFront} / {@link StaticContent}, and the composition of
 * those owners. Nothing else. Bind failure is non-fatal.
 */
public final class HttpEngineServer implements AutoCloseable {

    /** Grace period {@link HttpServer#stop} gives in-flight exchanges before hard-closing. */
    private static final int STOP_GRACE_SECONDS = 1;

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
    private final SseEndpoint sse;
    private final BuildJournal journal;
    private final Supplier<List<BuildMetrics.Entry>> metrics;
    private final Supplier<CacheSnapshot> cache;
    private final LiveVitals liveVitals;
    private final ApiRouter api = new ApiRouter();
    private final Consumer<String> log;
    private final @Nullable McpHandler mcp;
    private final @Nullable McpFront mcpFront;
    private final HttpHistoryApi historyApi;
    private final HttpProjectApi projectApi;
    private final HttpReadApi readApi;

    /**
     * Currently-running jobs (from {@code EngineServer}'s in-flight registry). Used to enrich
     * {@code GET /api/history} with live {@code requestId}/progress so a hard-refreshed dashboard
     * rebinds SSE, and to drive connect-time rehydrate callbacks.
     */
    private volatile Supplier<List<HttpLive.Run>> liveRuns = List::of;

    private volatile @Nullable HttpServer server;
    private volatile @Nullable ExecutorService executor;

    /**
     * Wire the engine's live-job view. Optional — tests leave the defaults (empty / no-op).
     *
     * @param liveRuns snapshot of holds currently running
     * @param onEventsConnect after a new {@code GET /api/events} subscription is live, deliver
     *     one mid-flight snapshot per running job to that subscription only
     */
    public void setLiveRunSupport(
            @Nullable Supplier<List<HttpLive.Run>> liveRuns, Consumer<HttpEvents.Subscription> onEventsConnect) {
        this.liveRuns = liveRuns != null ? liveRuns : List::of;
        sse.onConnect(onEventsConnect);
    }

    /**
     * As {@link #setLiveRunSupport(Supplier, Consumer)} with the engine's own membership probe for
     * one jid, so an MCP wait polls a hold-table lookup rather than the whole snapshot.
     */
    public void setLiveRunSupport(
            @Nullable Supplier<List<HttpLive.Run>> liveRuns,
            LongPredicate liveJid,
            Consumer<HttpEvents.Subscription> onEventsConnect) {
        setLiveRunSupport(liveRuns, onEventsConnect);
        if (mcp != null) mcp.liveJid(liveJid);
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
            @Nullable Consumer<String> log) {
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
        this.progressTokens = new ProgressTokenRegistry();
        // null when [mcp] enabled=false — dispatch 404s every /mcp path before reaching it.
        // MCP journal reads are redacted at the supplier — every consumer (history view=full,
        // diagnostics, project cards, run-wait summaries) sees the same defense-in-depth as REST.
        this.mcp = config.mcp().enabled()
                ? new McpHandler(
                        status,
                        jobs,
                        McpFront::projectMap,
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
        // jk_run answers the project page that follows the job it just started, authenticated.
        if (this.mcp != null) {
            this.mcp.dashboardLink(dir -> DashboardLinks.project(url(), tokens.tokenText(), ProjectIds.idOf(dir)));
        }
        this.sse = new SseEndpoint(events, liveVitals, progressTokens, this.log);
        this.mcpFront = this.mcp == null ? null : new McpFront(this.mcp, sse, version);
        this.historyApi = new HttpHistoryApi(journal, () -> this.liveRuns.get());
        this.projectApi = new HttpProjectApi(journal);
        this.readApi = new HttpReadApi(config, webRoot, logFile, status, jobs, metrics, cache, this::url);
        api.register("GET", "/api/status", readApi::handleStatus);
        api.register("GET", "/api/config", readApi::handleConfig);
        api.register("GET", "/api/events", sse::serveDashboard);
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
        // One thread per HTTP exchange; each handler binds the request's session itself.
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
    public @Nullable String url() {
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
                } catch (Exception unsent) {
                    // response already started (IllegalStateException) or client gone
                    Log.debug("handle: response already started (IllegalStateException) or client gone", unsent);
                }
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (HttpAdmission.isMcpPath(path)) {
            McpFront front = mcpFront;
            if (front == null) {
                HttpResponses.sendText(exchange, 404, "not found\n"); // [mcp] enabled = false
                return;
            }
            if (!tokens.authorizesMcp(exchange)) {
                tokens.challenge(exchange);
                return;
            }
            front.handle(exchange);
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

    /** Test seam: rebind rules for in-flight history rows. */
    HttpLive.@Nullable Run matchLiveRun(Map<String, Object> rec) {
        return historyApi.matchLiveRun(rec);
    }

    /** Test seam: the SSE endpoint, for its heartbeat and filter knobs. */
    SseEndpoint sse() {
        return sse;
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
