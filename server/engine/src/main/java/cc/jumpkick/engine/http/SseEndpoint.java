// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One long-lived SSE stream, dashboard or MCP framing: the connect-ordering contract that lets no
 * event fall between the hydrate snapshot and the live queue, then the heartbeat and batch drain.
 * Holds an SSE-budget slot (not an RPC admission permit) for the stream's life; a dead-client write
 * or the server's shutdown interrupt ends it.
 */
final class SseEndpoint {

    /** How often a quiet SSE stream writes a comment line — dead-client detection + proxy keepalive. */
    private static final long DEFAULT_HEARTBEAT_MILLIS = 15_000;

    private final HttpEvents events;
    private final LiveVitals liveVitals;
    private final ProgressTokenRegistry progressTokens;
    private final Consumer<String> log;

    /**
     * Invoked once after each dashboard SSE subscription is registered — delivers a compact
     * mid-flight {@code run-snapshot} (phases + progress + startedAt) to <em>that</em>
     * subscription only so a refreshed tab resumes without flooding the bus or blocking live
     * ticks behind a phase-by-phase replay.
     */
    private volatile Consumer<HttpEvents.Subscription> onConnect = s -> {};

    private long heartbeatMillis = DEFAULT_HEARTBEAT_MILLIS;

    SseEndpoint(HttpEvents events, LiveVitals liveVitals, ProgressTokenRegistry progressTokens, Consumer<String> log) {
        this.events = events;
        this.liveVitals = liveVitals;
        this.progressTokens = progressTokens;
        this.log = log;
    }

    /** The engine's connect-time rehydrate callback; {@code null} restores the no-op. */
    void onConnect(Consumer<HttpEvents.Subscription> onConnect) {
        this.onConnect = onConnect != null ? onConnect : s -> {};
    }

    /** Test seam: shrink the SSE heartbeat so quiet-stream behavior is testable in milliseconds. */
    void heartbeatMillis(long millis) {
        this.heartbeatMillis = millis;
    }

    /**
     * SSE stream: event frames plus comment heartbeats. Holds an SSE-budget slot (not an RPC
     * admission permit) for the stream's life; dead-client write and {@link #close} interrupt
     * end it.
     */
    void serveDashboard(HttpExchange exchange) throws IOException {
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
                onConnect.accept(subscription);
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

    /**
     * MCP progress SSE: same hub as {@code /api/events}, framed as Streamable-HTTP {@code message}
     * events with {@code notifications/jk/event} JSON-RPC bodies. Optional query filters: {@code
     * jid} (engine job id) or {@code progressToken} (bound from tools/call {@code
     * _meta.progressToken}).
     */
    void serveMcp(HttpExchange exchange) throws IOException {
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
    @Nullable
    Long resolveMcpEventFilter(@Nullable String query) {
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
}
