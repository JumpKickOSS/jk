// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.runtime.base.ProjectCard;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The MCP HTTP surface: the discovery document, JSON-RPC over {@code POST /mcp} with the request
 * body cap and a {@code 202} for notifications, the {@code Mcp-Session-Id} header both ways, and
 * the hand-off to the MCP-framed SSE stream. Reached only after {@link HttpTokenGate} has admitted
 * the exchange — MCP is token-gated even on loopback.
 */
final class McpFront {

    /** Streamable-HTTP session header: minted on {@code initialize}, echoed by the client afterwards. */
    static final String SESSION_HEADER = "Mcp-Session-Id";

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
        McpHandler.Reply reply = mcp.handle(body, sessionId);
        if (reply.openedSessionId() != null) {
            exchange.getResponseHeaders().set(SESSION_HEADER, reply.openedSessionId());
        }
        String response = reply.body();
        if (response == null || response.isEmpty()) {
            // JSON-RPC notification — accepted, no body.
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        HttpResponses.sendJson(exchange, 200, response);
    }

    /** Project metadata fallback for MCP {@code jk_project} — one card, one parse path. */
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
