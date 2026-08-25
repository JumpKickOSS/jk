// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * JSON-RPC 2.0 framing for {@code POST /mcp}: parse, batch, notification and error shape. It knows
 * nothing about tools — {@link Router} is the one seam, so the method table stays in
 * {@code McpHandler} and the envelope rules stay here.
 */
public final class McpRpc {

    private McpRpc() {}

    /** Dispatch one JSON-RPC method. Returns {@code null} for a notification-shaped method. */
    @FunctionalInterface
    public interface Router {
        @Nullable
        Object call(String method, Map<String, Object> params);
    }

    /**
     * Handle one HTTP body (JSON-RPC request or batch). Returns the JSON response body (object or
     * array). Notifications ({@code id} absent) yield an empty string — caller should respond 202
     * with no body or {@code {}}.
     */
    public static String handleBody(@Nullable String body, Router router) {
        if (body == null || body.isBlank()) {
            return error(null, -32700, "parse error: empty body");
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(body.trim());
        } catch (RuntimeException e) {
            return error(null, -32700, "parse error: " + e.getMessage());
        }
        if (parsed instanceof List<?> batch) {
            // JSON-RPC 2.0 batch: [] is a single -32600 error object, non-object entries answer
            // per-item -32600 (id null), and an all-notifications batch has no response body.
            if (batch.isEmpty()) {
                return error(null, -32600, "invalid request: empty batch");
            }
            List<Object> out = new ArrayList<>();
            for (Object item : batch) {
                if (item instanceof Map<?, ?> m) {
                    Object resp = dispatchOne(asParams(m), router);
                    if (resp != null) out.add(resp);
                } else {
                    out.add(errorMap(null, -32600, "invalid request: expected object"));
                }
            }
            if (out.isEmpty()) return ""; // all notifications — caller responds 202, no body
            return MiniJson.write(out);
        }
        if (parsed instanceof Map<?, ?> m) {
            Object resp = dispatchOne(asParams(m), router);
            if (resp == null) return ""; // notification
            return MiniJson.write(resp);
        }
        return error(null, -32600, "invalid request: expected object or array");
    }

    /** Null when the message was a notification (no response). */
    static @Nullable Object dispatchOne(Map<String, Object> req, Router router) {
        Object id = req.get("id");
        boolean notification = !req.containsKey("id");
        Object rawMethod = req.get("method");
        String method = rawMethod == null ? null : String.valueOf(rawMethod);
        if (method == null || method.isBlank()) {
            return notification ? null : errorMap(id, -32600, "invalid request: missing method");
        }
        Map<String, Object> params = req.get("params") instanceof Map<?, ?> p ? asParams(p) : Map.of();
        try {
            Object result = router.call(method, params);
            if (notification) return null;
            // Null result = a notification-shaped method. A client that (legally) sent it WITH an
            // id is making a request and hangs without a response — answer an empty result.
            return resultMap(id, result == null ? Map.of() : result);
        } catch (McpError e) {
            if (notification) return null;
            return errorMap(id, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            if (notification) return null;
            return errorMap(id, -32603, "internal error: " + e.getMessage());
        }
    }

    /**
     * The {@code initialize} handshake. Capabilities must name every method the router implements
     * and nothing else: a declared capability with no method is a client that calls it and gets
     * {@code -32601}.
     */
    public static Map<String, Object> initialize(String protocolVersion, String serverName, String version) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of("listChanged", false));
        caps.put("resources", Map.of("subscribe", false, "listChanged", false));
        caps.put("prompts", Map.of("listChanged", false));
        // Streamable-HTTP progress: GET /mcp with Accept: text/event-stream.
        caps.put("logging", Map.of());
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("jk/events", Map.of("sse", "GET /mcp", "notification", "notifications/jk/event"));
        caps.put("experimental", experimental);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", serverName);
        serverInfo.put("version", version);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", protocolVersion);
        result.put("capabilities", caps);
        result.put("serverInfo", serverInfo);
        result.put("instructions", McpTools.INSTRUCTIONS);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asParams(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    static Map<String, Object> resultMap(@Nullable Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    static Map<String, Object> errorMap(@Nullable Object id, int code, @Nullable String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return m;
    }

    private static String error(@Nullable Object id, int code, @Nullable String message) {
        return MiniJson.write(errorMap(id, code, message));
    }
}
