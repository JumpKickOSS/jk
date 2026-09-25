// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.JkHttpConfig;
import com.sun.net.httpserver.HttpExchange;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The three admission budgets — RPC, dashboard SSE, MCP SSE — and the rule that picks one for an
 * exchange. Long-lived streams draw from their own budgets, separate from RPC admission (an open
 * stream holds its slot for the connection's life, so streams on the RPC semaphore would let
 * {@code maxConcurrentRequests} EventSource tabs starve every other endpoint) and from each other (a
 * runaway agent must not evict the dashboard, or vice versa).
 */
final class HttpAdmission {

    private final JkHttpConfig config;
    private final Semaphore rpc;
    private final Semaphore webSse;
    private final Semaphore mcpSse;

    HttpAdmission(JkHttpConfig config) {
        this.config = config;
        this.rpc = new Semaphore(config.effectiveMaxConcurrentRequests());
        this.webSse = new Semaphore(config.maxEventStreams());
        this.mcpSse = new Semaphore(config.mcp().maxEventStreams());
    }

    /** One exchange's lane: the budget it draws from, whether it is a stream, and what a full budget says. */
    record Lane(Semaphore gate, boolean stream, String busy) {
        boolean tryAcquire() {
            return gate.tryAcquire();
        }

        void release() {
            gate.release();
        }
    }

    Lane laneFor(HttpExchange exchange) {
        if (!isEventStreamRequest(exchange)) return new Lane(rpc, false, "engine busy\n");
        return isMcpPath(exchange.getRequestURI().getPath())
                ? new Lane(mcpSse, true, "too many MCP event streams\n")
                : new Lane(webSse, true, "too many event streams\n");
    }

    /**
     * Matches exactly the requests that enter a long-lived stream loop ({@code GET /api/events} and
     * the MCP SSE GET) — these draw from the SSE budget, not RPC admission.
     */
    static boolean isEventStreamRequest(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) return false;
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/events")) return true;
        return isMcpPath(path) && acceptsEventStream(exchange);
    }

    static boolean isMcpPath(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/");
    }

    static boolean acceptsEventStream(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (accept == null || accept.isBlank()) return false;
        return accept.toLowerCase(Locale.ROOT).contains("text/event-stream");
    }

    /**
     * {@link AdmissionYield} over the RPC gate: MCP long-polls park here after releasing their
     * permit, so 16 waiting agents cannot 503 the surface (including the {@code cancel} that
     * would un-wedge them). Reacquire is uninterruptible — the balancing {@code release()} in
     * the server's handle method must never release a permit this thread does not hold.
     */
    <T> T yieldingRpc(Supplier<T> blocking) {
        rpc.release();
        try {
            return blocking.get();
        } finally {
            rpc.acquireUninterruptibly();
        }
    }

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
    int liveEventStreams() {
        int web = config.maxEventStreams() - webSse.availablePermits();
        int mcp = config.mcp().maxEventStreams() - mcpSse.availablePermits();
        return Math.max(0, web) + Math.max(0, mcp);
    }

    /** Test seam: the RPC gate, so a saturated-server {@code 503} is deterministically testable. */
    Semaphore rpc() {
        return rpc;
    }

    /** Test seam: the web-UI SSE budget, so over-cap stream rejection is deterministically testable. */
    Semaphore webSse() {
        return webSse;
    }

    /** Test seam: the MCP SSE budget — independent of {@link #webSse()}. */
    Semaphore mcpSse() {
        return mcpSse;
    }
}
