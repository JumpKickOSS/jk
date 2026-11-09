// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.decisions.Dashboards;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EventCatalog;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One long-lived SSE stream, dashboard or MCP framing: the connect-ordering contract that lets no
 * event fall between the hydrate snapshot and the live queue, then the heartbeat and batch drain.
 * Holds an SSE-budget slot (not an RPC admission permit) for the stream's life; a dead-client write
 * or the server's shutdown interrupt ends it.
 *
 * <p>A dashboard stream is also how the engine knows the tab: it opens with a {@code presence}
 * frame naming the stream, repeats it every {@link Dashboards#PING_EVERY}, and the tab answers each
 * with {@code POST /api/events/<stream>} saying whether it is visible and may notify. EventSource
 * cannot write upstream, so that post is the stream's return half, not a second channel: it names
 * no tab the stream does not hold, and the tab leaves {@link Dashboards} when its stream ends.
 */
final class SseEndpoint {

    /**
     * How often a quiet SSE stream writes a comment line. The JDK server never reads a socket while
     * its handler streams, so a write is the only way a stream learns its client is gone: the first
     * write after the peer closes draws a reset and the next fails. A short interval frees a closed
     * page's slot (and its vitals subscriber) within two seconds; a dozen bytes a second is nothing
     * to a live tab, and a comment line wakes no handler in the page.
     */
    static final long DEFAULT_HEARTBEAT_MILLIS = 1_000;

    /** Where {@code POST /api/events/<stream>} reports land. */
    static final String PRESENCE_PREFIX = "/api/events/";

    private final HttpEvents events;
    private final Dashboards dashboards;
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
    private long pingMillis = Dashboards.PING_EVERY.toMillis();

    SseEndpoint(
            HttpEvents events,
            Dashboards dashboards,
            LiveVitals liveVitals,
            ProgressTokenRegistry progressTokens,
            Consumer<String> log) {
        this.events = events;
        this.dashboards = dashboards;
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

    /** Test seam: shrink the presence ping. */
    void pingMillis(long millis) {
        this.pingMillis = millis;
    }

    /**
     * {@code POST /api/events/<stream>} with {@code {"visible": bool, "permission": "default" |
     * "granted" | "denied" | "unsupported"}}: the tab on that stream says how it stands. {@code 200},
     * {@code 404} once the stream has ended (the tab's next connect names a new one), {@code 400} for
     * a body that is not an object.
     */
    void report(HttpExchange exchange) throws IOException {
        String stream = exchange.getRequestURI().getPath().substring(PRESENCE_PREFIX.length());
        Object parsed = MiniJson.parse(HttpRequests.body(exchange));
        if (!(parsed instanceof Map<?, ?> body)) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "the body is a JSON object").toString());
            return;
        }
        boolean visible = Boolean.TRUE.equals(body.get("visible"));
        Dashboards.Permission permission = Dashboards.Permission.parse(MiniJson.str(body, "permission"));
        if (!dashboards.report(stream, visible, permission)) {
            HttpResponses.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such stream: " + stream).toString());
            return;
        }
        HttpResponses.sendJson(exchange, 200, "{}");
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
        String stream = UUID.randomUUID().toString();
        JsonOut presence = JsonOut.object().put("stream", stream).put("every", pingMillis);
        try {
            dashboards.open(
                    stream, d -> events.deliverTo(subscription, EventCatalog.SSE_NOTIFY, JsonOut.rawObject(d.toMap())));
            events.deliverTo(subscription, EventCatalog.SSE_PRESENCE, presence);
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
            long nextPing = Clock.SYSTEM.nanos() + pingMillis * 1_000_000L;
            while (true) {
                if (Clock.SYSTEM.nanos() - nextPing >= 0) {
                    events.deliverTo(subscription, EventCatalog.SSE_PRESENCE, presence);
                    nextPing = Clock.SYSTEM.nanos() + pingMillis * 1_000_000L;
                }
                String first = subscription.next(Math.min(heartbeatMillis, pingMillis));
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
            dashboards.close(stream);
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
        Supplier<@Nullable Long> filter =
                mcpEventFilter(exchange.getRequestURI().getRawQuery());
        Long jid = filter == null ? null : filter.get();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (jid != null) {
            exchange.getResponseHeaders().set("X-Jk-Jid", Long.toString(jid));
        }
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        String hello = jid == null ? ": mcp-events connected\n\n" : ": mcp-events connected jid=" + jid + "\n\n";
        try (HttpEvents.Subscription subscription = events.subscribeFollowing(HttpEvents.FrameStyle.MCP, filter)) {
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
     * The optional SSE filter a query names; {@code null} is every job. {@code jid} wins over {@code
     * progressToken}. A token is resolved per event, so a stream opened before the {@code
     * tools/call} that binds it follows that job once it starts, and never another job before.
     */
    @Nullable
    Supplier<@Nullable Long> mcpEventFilter(@Nullable String query) {
        String rid = HttpQuery.queryParamLenient(query, "jid");
        if (rid != null && !rid.isBlank()) {
            try {
                long jid = Long.parseLong(rid.trim());
                return () -> jid;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String tok = HttpQuery.queryParamLenient(query, "progressToken");
        if (tok != null && !tok.isBlank()) {
            String token = tok.trim();
            return () -> progressTokens.resolve(token);
        }
        return null;
    }
}
