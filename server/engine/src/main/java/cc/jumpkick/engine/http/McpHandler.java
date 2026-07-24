// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.util.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Thin MCP (Model Context Protocol) JSON-RPC surface for agents (JK-1095). Hosted on the engine
 * HTTP server next to the web dashboard — not a second build engine.
 *
 * <p>Transport: {@code POST /mcp} with a JSON-RPC 2.0 body (single object or batch array). Responses
 * are {@code application/json}. Same bearer-token policy as other mutating engine HTTP endpoints.
 *
 * <p>Tools project the same facts as CLI JSONL / {@code /api/*} (status, build trigger, project
 * metadata, history). Live progress: {@code GET /mcp} with {@code Accept: text/event-stream}
 * (MCP {@code notifications/jk/event}); filter with {@code ?requestId=} or {@code
 * ?progressToken=} (bound from tools/call {@code _meta.progressToken}). See {@code
 * docs/machine-output.md}.
 */
public final class McpHandler {

    /** MCP protocol version we advertise (Streamable-HTTP era baseline). */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    public static final String SERVER_NAME = "jk-engine";

    private final Supplier<StatusSnapshot> status;
    private final EngineHttpJobs jobs;
    private final Function<String, Map<String, Object>> projectLookup;
    private final Supplier<List<String>> historyRaw;
    private final String version;
    private final ProgressTokenRegistry progressTokens;

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version) {
        this(status, jobs, projectLookup, historyRaw, version, new ProgressTokenRegistry());
    }

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version,
            ProgressTokenRegistry progressTokens) {
        this.status = Objects.requireNonNull(status);
        this.jobs = Objects.requireNonNull(jobs);
        this.projectLookup = Objects.requireNonNull(projectLookup);
        this.historyRaw = Objects.requireNonNull(historyRaw);
        this.version = version == null ? "0" : version;
        this.progressTokens = progressTokens == null ? new ProgressTokenRegistry() : progressTokens;
    }

    /**
     * Handle one HTTP body (JSON-RPC request or batch). Returns the JSON response body (object or
     * array). Notifications ({@code id} absent) yield an empty string — caller should respond 202
     * with no body or {@code {}}.
     */
    public String handleBody(String body) {
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
            List<Object> out = new ArrayList<>();
            for (Object item : batch) {
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> req = (Map<String, Object>) m;
                    Object resp = dispatchOne(req);
                    if (resp != null) out.add(resp);
                }
            }
            return MiniJson.write(out);
        }
        if (parsed instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = (Map<String, Object>) m;
            Object resp = dispatchOne(req);
            if (resp == null) return ""; // notification
            return MiniJson.write(resp);
        }
        return error(null, -32600, "invalid request: expected object or array");
    }

    /** Null when the message was a notification (no response). */
    Object dispatchOne(Map<String, Object> req) {
        Object id = req.get("id");
        boolean notification = !req.containsKey("id");
        String method = string(req.get("method"));
        if (method == null || method.isBlank()) {
            return notification ? null : errorMap(id, -32600, "invalid request: missing method");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = req.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();

        try {
            Object result =
                    switch (method) {
                        case "initialize" -> initialize(params);
                        case "notifications/initialized", "initialized" -> null; // notification
                        case "ping" -> Map.of();
                        case "tools/list" -> toolsList();
                        case "tools/call" -> toolsCall(params);
                        case "resources/list" -> Map.of("resources", List.of());
                        case "prompts/list" -> Map.of("prompts", List.of());
                        default -> throw new McpError(-32601, "method not found: " + method);
                    };
            if (result == null) return null; // notification ack
            if (notification) return null;
            return resultMap(id, result);
        } catch (McpError e) {
            if (notification) return null;
            return errorMap(id, e.code, e.getMessage());
        } catch (RuntimeException e) {
            if (notification) return null;
            return errorMap(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tools", Map.of("listChanged", false));
        // Streamable-HTTP progress: GET /mcp with Accept: text/event-stream.
        caps.put("logging", Map.of());
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("jk/events", Map.of("sse", "GET /mcp", "notification", "notifications/jk/event"));
        caps.put("experimental", experimental);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", version);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", caps);
        result.put("serverInfo", serverInfo);
        result.put(
                "instructions",
                "JumpKick engine MCP. Prefer tools for multi-turn agent work. Live build progress: "
                        + "GET /mcp with Accept: text/event-stream (notifications/jk/event); "
                        + "each event params object carries aggregate progress (0–100 or null) plus "
                        + "step/module/error fields aligned with CLI JSONL. "
                        + "filter with ?requestId=N or ?progressToken=T (pass _meta.progressToken on "
                        + "tools/call). Or GET /api/events (dashboard SSE). One-shot CLI: jk … "
                        + "--output json. See docs/machine-output.md.");
        return result;
    }

    private Map<String, Object> toolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool(
                "jk_status",
                "Engine vitals (pid, version, heap, active jobs). Same facts as GET /api/status and "
                        + "jk engine status --output json.",
                objectSchema(Map.of())));
        tools.add(tool(
                "jk_build",
                "Start a workspace/module build for dir (async). Returns requestId; stream progress "
                        + "via GET /mcp?requestId=N (or ?progressToken=T with _meta.progressToken) "
                        + "Accept: text/event-stream. Same as POST /api/build.",
                objectSchema(Map.of(
                        "dir",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Absolute path to project or workspace root (jk.toml)")))));
        tools.add(tool(
                "jk_test",
                "Start a true test-only job for dir (async; compile + tests, no package — same as "
                        + "jk test). Journal kind test. Progress: GET /mcp?requestId=N. Returns requestId.",
                objectSchema(Map.of(
                        "dir",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Absolute path to project or workspace root (jk.toml)")))));
        tools.add(tool(
                "jk_lock",
                "Resolve dependencies and write jk.lock for dir (async). Progress: GET /mcp?requestId=N.",
                objectSchema(
                        Map.of("dir", Map.of("type", "string", "description", "Absolute path containing jk.toml")))));
        tools.add(tool(
                "jk_cancel",
                "Cancel an in-flight HTTP/MCP job by requestId (grace then force workers; JK-1096).",
                objectSchema(Map.of(
                        "requestId",
                        Map.of("type", "integer", "description", "Id returned by jk_build / jk_test / jk_lock")))));
        tools.add(tool(
                "jk_project",
                "Parse project metadata from dir/jk.toml (coord, description).",
                objectSchema(
                        Map.of("dir", Map.of("type", "string", "description", "Absolute path containing jk.toml")))));
        tools.add(tool(
                "jk_history",
                "Recent build journal entries (JSON array of records). Same source as GET /api/history.",
                objectSchema(Map.of(
                        "limit", Map.of("type", "integer", "description", "Max entries (default 20, max 200)")))));
        return Map.of("tools", tools);
    }

    private Map<String, Object> toolsCall(Map<String, Object> params) {
        String name = string(params.get("name"));
        if (name == null || name.isBlank()) throw new McpError(-32602, "tools/call requires name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        String progressToken = progressTokenOf(params);

        Object payload =
                switch (name) {
                    case "jk_status" -> statusPayload();
                    case "jk_build" -> jobPayload("build", args, jobs::triggerBuild, progressToken);
                    case "jk_test" -> jobPayload("test", args, jobs::triggerTest, progressToken);
                    case "jk_lock" -> jobPayload("lock", args, jobs::triggerLock, progressToken);
                    case "jk_cancel" -> cancelPayload(args);
                    case "jk_project" -> projectPayload(args);
                    case "jk_history" -> historyPayload(args);
                    default -> throw new McpError(-32602, "unknown tool: " + name);
                };
        return toolResult(MiniJson.write(payload), false);
    }

    /** MCP progress token from {@code params._meta.progressToken} (string or number). */
    private static String progressTokenOf(Map<String, Object> params) {
        Object meta = params.get("_meta");
        if (!(meta instanceof Map<?, ?> m)) return null;
        Object tok = m.get("progressToken");
        if (tok == null) return null;
        String s = String.valueOf(tok).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private Map<String, Object> statusPayload() {
        StatusSnapshot s = status.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("type", "status");
        m.put("version", s.version());
        m.put("pid", s.pid());
        m.put("startedAt", s.startedAtMillis());
        m.put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000));
        m.put("activeRequests", s.activeRequests());
        m.put("activePipelines", s.activePipelines());
        m.put("heapUsedBytes", s.heapUsedBytes());
        m.put("heapCommittedBytes", s.heapCommittedBytes());
        m.put("heapMaxBytes", s.heapMaxBytes());
        m.put("rssBytes", s.rssBytes());
        m.put("cores", s.cores());
        return m;
    }

    private Map<String, Object> jobPayload(
            String kind,
            Map<String, Object> args,
            java.util.function.Function<String, Long> trigger,
            String progressToken) {
        String dir = string(args.get("dir"));
        if (dir == null || dir.isBlank()) throw new McpError(-32602, "requires arguments.dir");
        try {
            long requestId = trigger.apply(dir);
            if (progressToken != null) progressTokens.bind(progressToken, requestId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schema", 1);
            m.put("type", kind + "-accepted");
            m.put("kind", kind);
            m.put("requestId", requestId);
            m.put("dir", dir);
            m.put("events", "/api/events");
            m.put("mcpEvents", "GET /mcp?requestId=" + requestId);
            if (progressToken != null) {
                m.put("progressToken", progressToken);
                m.put("mcpEventsByToken", "GET /mcp?progressToken=" + progressToken);
            }
            m.put(
                    "note",
                    "Job started asynchronously. Stream progress via GET /mcp?requestId="
                            + requestId
                            + " (Accept: text/event-stream) or GET /api/events. Cancel with jk_cancel.");
            return m;
        } catch (IllegalStateException e) {
            throw new McpError(-32000, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
    }

    private Map<String, Object> cancelPayload(Map<String, Object> args) {
        Object raw = args.get("requestId");
        if (!(raw instanceof Number n)) throw new McpError(-32602, "jk_cancel requires arguments.requestId");
        long id = n.longValue();
        boolean ok = jobs.cancel(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("type", "cancel");
        m.put("requestId", id);
        m.put("cancelled", ok);
        if (!ok) m.put("note", "unknown or already finished requestId");
        return m;
    }

    private Map<String, Object> projectPayload(Map<String, Object> args) {
        String dir = string(args.get("dir"));
        if (dir == null || dir.isBlank()) throw new McpError(-32602, "jk_project requires arguments.dir");
        Map<String, Object> m = new LinkedHashMap<>(projectLookup.apply(dir));
        m.putIfAbsent("schema", 1);
        m.putIfAbsent("type", "project");
        m.put("dir", dir);
        return m;
    }

    private Map<String, Object> historyPayload(Map<String, Object> args) {
        int limit = 20;
        Object lim = args.get("limit");
        if (lim instanceof Number n) limit = n.intValue();
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        List<String> raw = historyRaw.get();
        if (raw.size() > limit) raw = raw.subList(0, limit);
        // Return as parsed objects when possible for agent convenience.
        List<Object> records = new ArrayList<>();
        for (String r : raw) {
            try {
                records.add(MiniJson.parse(r));
            } catch (RuntimeException e) {
                records.add(r);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("type", "history");
        m.put("records", records);
        return m;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        return t;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        List<String> required = new ArrayList<>();
        for (String k : properties.keySet()) {
            if ("dir".equals(k)) required.add(k);
        }
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        if (isError) result.put("isError", true);
        return result;
    }

    private static Map<String, Object> resultMap(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> errorMap(Object id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return m;
    }

    private static String error(Object id, int code, String message) {
        return MiniJson.write(errorMap(id, code, message));
    }

    private static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static final class McpError extends RuntimeException {
        final int code;

        McpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
