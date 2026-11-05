// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.mcp.McpRevision;
import cc.jumpkick.runtime.base.ProjectCard;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The MCP HTTP surface: the discovery document, JSON-RPC over {@code POST /mcp} with the request
 * body cap and a {@code 202} for notifications and responses, the {@code Mcp-Session-Id} header
 * both ways, the {@code MCP-Protocol-Version} header in, a request's response turned into an event
 * stream when its call sends the client a request of its own, and the hand-off to the MCP-framed
 * SSE stream. Reached only after {@link HttpTokenGate} has admitted
 * the exchange — MCP is token-gated even on loopback.
 */
final class McpFront {

    /** Streamable-HTTP session header: minted on {@code initialize}, echoed by the client afterwards. */
    static final String SESSION_HEADER = "Mcp-Session-Id";

    /** The project a single-project client ({@code jk mcp}) is for; its connection needs no {@code dir}. */
    static final String PROJECT_HEADER = "Jk-Project";

    /** The revision the session negotiated, sent by the client on every request after {@code initialize}. */
    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    private final McpHandler mcp;
    private final SseEndpoint sse;
    private final String engineVersion;

    McpFront(McpHandler mcp, SseEndpoint sse, String engineVersion) {
        this.mcp = mcp;
        this.sse = sse;
        this.engineVersion = engineVersion;
    }

    /**
     * MCP Streamable-HTTP style: {@code POST /mcp} with JSON-RPC body; {@code GET /mcp} with {@code
     * Accept: text/event-stream} opens an SSE progress stream ({@code notifications/jk/event});
     * otherwise GET returns a small discovery document.
     */
    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET") || method.equals("HEAD")) {
            if (method.equals("GET") && HttpAdmission.acceptsEventStream(exchange)) {
                sse.serveMcp(exchange);
                return;
            }
            HttpResponses.sendJson(
                    exchange,
                    200,
                    JsonOut.object()
                            .put("schema", 1)
                            .put("type", "mcp-discovery")
                            .put("protocolVersion", McpRevision.LATEST.wire())
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
        String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (method.equals("DELETE")) {
            // The client is done with its connection; an id this engine never issued is a 404.
            exchange.sendResponseHeaders(mcp.closeConnection(sessionId) ? 204 : 404, -1);
            return;
        }
        if (!method.equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, DELETE");
            HttpResponses.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        String body = HttpRequests.body(exchange);
        Stream stream = HttpAdmission.acceptsEventStream(exchange) ? new Stream(exchange) : null;
        McpHandler.Reply reply;
        try {
            reply = mcp.handle(
                    body,
                    sessionId,
                    exchange.getRequestHeaders().getFirst(PROJECT_HEADER),
                    exchange.getRequestHeaders().getFirst(PROTOCOL_HEADER),
                    stream == null ? null : stream::send);
        } catch (IllegalArgumentException e) {
            HttpResponses.sendText(exchange, 400, e.getMessage() + "\n");
            return;
        }
        String response = reply.body();
        if (stream != null && stream.opened()) {
            // The call sent the client a request first: its response is the stream's last event.
            if (response != null && !response.isEmpty()) stream.event(response);
            stream.close();
            return;
        }
        if (reply.openedSessionId() != null) {
            exchange.getResponseHeaders().set(SESSION_HEADER, reply.openedSessionId());
        }
        if (response == null || response.isEmpty()) {
            // JSON-RPC notification or response — accepted, no body.
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        HttpResponses.sendJson(exchange, 200, response);
    }

    /**
     * A {@code POST /mcp} response that becomes an event stream on the first message the engine
     * sends the client while the request is outstanding. Until then nothing is committed, and a
     * request that sends nothing answers plain JSON.
     */
    private static final class Stream {

        private final HttpExchange exchange;
        private @Nullable OutputStream out;

        Stream(HttpExchange exchange) {
            this.exchange = exchange;
        }

        synchronized boolean opened() {
            return out != null;
        }

        synchronized void send(Map<String, Object> message) throws IOException {
            event(MiniJson.write(message));
        }

        synchronized void event(String json) throws IOException {
            OutputStream o = out;
            if (o == null) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, 0);
                o = exchange.getResponseBody();
                out = o;
            }
            o.write(("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        }

        synchronized void close() {
            OutputStream o = out;
            if (o == null) return;
            try {
                o.close();
            } catch (IOException ignored) {
                // The client hung up; the call's answer is lost with it.
            }
        }
    }

    /** Project metadata fallback for MCP {@code project} — one card, one parse path. */
    static Map<String, Object> projectMap(String dir) {
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
}
