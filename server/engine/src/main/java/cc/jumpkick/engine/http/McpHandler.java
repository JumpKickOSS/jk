// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.http.mcp.McpProjectCards;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSession;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.plugin.protocol.MiniJson;
import cc.jumpkick.util.PathUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Thin MCP (Model Context Protocol) JSON-RPC surface for agents. Hosted on the engine
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
    private final McpSession session = new McpSession();
    private final Supplier<List<HttpLive.Run>> liveRuns;
    private final AdmissionYield admissionYield;

    /**
     * Raw JSON of the finished journal record for a jid, or {@code null} while absent/running.
     * The journal-backed form memoizes the run dir so wait-loop polls are one file read, never a
     * full {@code rawRecords} re-scan.
     */
    private final LongFunction<String> finishedRecords;

    /**
     * Shared memoized cache/store walker (same supplier as {@code GET /api/cache}) — {@code
     * jk_disk}/{@code jk_doctor}/{@code jk://disk} must not re-walk multi-GiB stores per call.
     * Optional wiring; {@code null} falls back to a fresh exclusive capture.
     */
    private volatile Supplier<CacheSnapshot> cacheSnapshot;

    /**
     * The engine's plan-vs-maintenance lock ({@code cacheGate}); {@code jk_disk clean|nuke} must
     * hold its write side (plus {@code .prune.lock}) before deleting. {@code null} only in tests
     * with no engine — the file lock still applies there.
     */
    private volatile ReentrantReadWriteLock cacheGate;

    /** Age after which a live job with no progress is marked stalled. */
    static final long STALL_MS = 60_000;

    /** Hard cap on a single wait; agents re-issue {@code jk_job action=wait} to keep waiting. */
    static final int MAX_WAIT_S = 3600;

    static final String INSTRUCTIONS = "Bind first: jk_bind {dir}. "
            + "Failing build / where is it failing → jk_diagnostics. "
            + "Why dep X → jk_why. Slow / next-build ETA → jk_explain. "
            + "Frozen / kill → jk_status then jk_job cancel. "
            + "Run / test / lock → jk_run (wait defaults true). "
            + "Add/remove deps → jk_deps. Git/path as workspace member → jk_workspace. "
            + "java= → jk_manifest. Heap / nerd-font / CI → jk_config. "
            + "Disk → jk_disk. Host health → jk_doctor. "
            + "History is summaries only. Live progress: GET /mcp?requestId=N "
            + "(Accept: text/event-stream).";

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version) {
        this(status, jobs, projectLookup, historyRaw, version, new ProgressTokenRegistry(), List::of);
    }

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version,
            ProgressTokenRegistry progressTokens) {
        this(status, jobs, projectLookup, historyRaw, version, progressTokens, List::of);
    }

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version,
            ProgressTokenRegistry progressTokens,
            Supplier<List<HttpLive.Run>> liveRuns) {
        this(status, jobs, projectLookup, historyRaw, version, progressTokens, liveRuns, AdmissionYield.NONE);
    }

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version,
            ProgressTokenRegistry progressTokens,
            Supplier<List<HttpLive.Run>> liveRuns,
            AdmissionYield admissionYield) {
        this(status, jobs, projectLookup, historyRaw, version, progressTokens, liveRuns, admissionYield, null);
    }

    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version,
            ProgressTokenRegistry progressTokens,
            Supplier<List<HttpLive.Run>> liveRuns,
            AdmissionYield admissionYield,
            LongFunction<String> finishedRecords) {
        this.status = Objects.requireNonNull(status);
        this.jobs = Objects.requireNonNull(jobs);
        this.projectLookup = Objects.requireNonNull(projectLookup);
        this.historyRaw = Objects.requireNonNull(historyRaw);
        this.version = version == null ? "0" : version;
        this.progressTokens = progressTokens == null ? new ProgressTokenRegistry() : progressTokens;
        this.liveRuns = liveRuns == null ? List::of : liveRuns;
        this.admissionYield = admissionYield == null ? AdmissionYield.NONE : admissionYield;
        this.finishedRecords = finishedRecords != null
                ? finishedRecords
                : jid -> {
                    Map<String, Object> rec = McpDiagnostics.findByRequestId(this.historyRaw.get(), jid);
                    return rec == null ? null : MiniJson.write(rec);
                };
    }

    /** Wire the shared cache/store snapshot supplier (memoized in the live engine). Optional. */
    public void cacheSnapshot(Supplier<CacheSnapshot> cacheSnapshot) {
        this.cacheSnapshot = cacheSnapshot;
    }

    /** Wire the engine's cache maintenance gate so destructive disk tools take the real locks. */
    public void cacheGate(ReentrantReadWriteLock cacheGate) {
        this.cacheGate = cacheGate;
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
            // JSON-RPC 2.0 batch: [] is a single -32600 error object, non-object entries answer
            // per-item -32600 (id null), and an all-notifications batch has no response body.
            if (batch.isEmpty()) {
                return error(null, -32600, "invalid request: empty batch");
            }
            List<Object> out = new ArrayList<>();
            for (Object item : batch) {
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> req = (Map<String, Object>) m;
                    Object resp = dispatchOne(req);
                    if (resp != null) out.add(resp);
                } else {
                    out.add(errorMap(null, -32600, "invalid request: expected object"));
                }
            }
            if (out.isEmpty()) return ""; // all notifications — caller responds 202, no body
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
                        case "resources/list" -> resourcesList();
                        case "resources/read" -> resourcesRead(params);
                        case "prompts/list" -> promptsList();
                        case "prompts/get" -> promptsGet(params);
                        case "logging/setLevel" -> Map.of(); // declared capability; engine log level is fixed
                        default -> throw new McpError(-32601, "method not found: " + method);
                    };
            if (notification) return null;
            // Null result = a notification-shaped method. A client that (legally) sent it WITH an
            // id is making a request and hangs without a response — answer an empty result.
            return resultMap(id, result == null ? Map.of() : result);
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
        caps.put("resources", Map.of("subscribe", false, "listChanged", false));
        caps.put("prompts", Map.of("listChanged", false));
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
        result.put("instructions", INSTRUCTIONS);
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
                "Start a workspace/module build for dir (async). Returns jid; stream progress "
                        + "via GET /mcp?jid=N (or ?progressToken=T with _meta.progressToken) "
                        + "Accept: text/event-stream. Same as POST /api/build.",
                objectSchema(Map.of(
                        "dir",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Project/workspace root (jk.toml): absolute, ~/…, or home-relative")))));
        tools.add(tool(
                "jk_test",
                "Start a true test-only job for dir (async; compile + tests, no package — same as "
                        + "jk test). Journal kind test. Progress: GET /mcp?jid=N. Returns jid.",
                objectSchema(Map.of(
                        "dir",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Project/workspace root (jk.toml): absolute, ~/…, or home-relative")))));
        tools.add(tool(
                "jk_lock",
                "Resolve dependencies and write jk-lock.toml for dir (async). Progress: GET /mcp?jid=N.",
                objectSchema(Map.of(
                        "dir",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Project/workspace root (jk.toml): absolute, ~/…, or home-relative")))));
        tools.add(tool(
                "jk_cancel",
                "Cancel an in-flight job by jid, or every live job for a dir. Grace then force workers.",
                objectSchema(Map.of(
                        "jid",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Job id from jk_build / jk_test / jk_lock / job-start"),
                        "dir",
                        Map.of("type", "string", "description", "Cancel every live job for this checkout")))));
        tools.add(tool(
                "jk_bind",
                "Set the default workspace for later tools (omit dir after this). Returns a project card.",
                objectSchema(
                        Map.of(
                                "dir",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Project/workspace root (jk.toml): absolute, ~/…, or home-relative")),
                        List.of("dir"))));
        tools.add(tool(
                "jk_project",
                "Project card (coord, java, members, last run). dir optional after jk_bind.",
                objectSchema(Map.of(
                        "dir",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Project root (jk.toml): absolute, ~/…, or home-relative")))));
        tools.add(tool(
                "jk_history",
                "Recent runs as summaries (id, success, failed modules, diagnostic count). "
                        + "Default view=summary. Filters: dir, projectId, success, kind, limit, next.",
                objectSchema(Map.of(
                        "limit",
                        Map.of("type", "integer", "description", "Max rows (default 10, max 200)"),
                        "next",
                        Map.of("type", "integer", "description", "Skip this many matching rows (from prior next)"),
                        "dir",
                        Map.of("type", "string", "description", "Filter to this checkout (default: bound dir)"),
                        "projectId",
                        Map.of("type", "string", "description", "Filter to this durable project id"),
                        "kind",
                        Map.of("type", "string", "description", "build | test | lock | …"),
                        "success",
                        Map.of("type", "boolean", "description", "Only successful or only failed runs"),
                        "view",
                        Map.of("type", "string", "description", "summary (default) or full (raw journal — avoid)")))));
        tools.add(tool(
                "jk_diagnostics",
                "Structured compiler/test failures for last-fail (default) or a history id. "
                        + "Unique by file:line:col + first message line.",
                objectSchema(Map.of(
                        "run",
                        Map.of("type", "string", "description", "last-fail (default) or history id"),
                        "dir",
                        Map.of("type", "string", "description", "Checkout filter (default: bound dir)"),
                        "module",
                        Map.of("type", "string", "description", "Filter by module dir substring"),
                        "severity",
                        Map.of("type", "string", "description", "error | warning"),
                        "unique",
                        Map.of("type", "boolean", "description", "Collapse duplicates (default true)"),
                        "limit",
                        Map.of("type", "integer", "description", "Max rows (default 20)"),
                        "next",
                        Map.of("type", "integer", "description", "Skip this many unique rows")))));
        tools.add(tool(
                "jk_run",
                "Start a job (build|test|lock|update|format|native|image|assemble|compile|clean). "
                        + "wait defaults true. dir optional after jk_bind. Aliases: jk_build/jk_test/jk_lock.",
                objectSchema(Map.of(
                        "kind",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "build|test|lock|update|format|native|image|assemble|compile|clean"),
                        "dir",
                        Map.of("type", "string", "description", "Project root (default: bound dir)"),
                        "modules",
                        Map.of("type", "array", "items", Map.of("type", "string"), "description", "Module names/globs"),
                        "include_tags",
                        Map.of("type", "array", "items", Map.of("type", "string")),
                        "exclude_tags",
                        Map.of("type", "array", "items", Map.of("type", "string")),
                        "suites",
                        Map.of("type", "array", "items", Map.of("type", "string")),
                        "skip_tests",
                        Map.of("type", "boolean"),
                        "wait",
                        Map.of("type", "boolean", "description", "Block until finish (default true)"),
                        "timeout_s",
                        Map.of("type", "integer", "description", "Wait timeout seconds (default 600, max 3600)")))));
        tools.add(tool(
                "jk_job",
                "get / wait / cancel a job. Omit jid to use the latest live job for the bound dir.",
                objectSchema(Map.of(
                        "action",
                        Map.of("type", "string", "description", "get | wait | cancel"),
                        "jid",
                        Map.of("type", "integer"),
                        "timeout_s",
                        Map.of("type", "integer")))));
        tools.add(tool(
                "jk_why",
                "Why a dependency is on the graph. query is group, artifact, or substring.",
                objectSchema(
                        Map.of(
                                "query",
                                Map.of("type", "string", "description", "group, artifact, or substring"),
                                "dir",
                                Map.of("type", "string")),
                        List.of("query"))));
        tools.add(tool(
                "jk_explain",
                "Forecast the next build: dirty modules and cache hits (jk explain).",
                objectSchema(Map.of("dir", Map.of("type", "string")))));
        tools.add(tool(
                "jk_outdated",
                "Declared deps newer than the lock (current / compatible / latest). Read-only.",
                objectSchema(Map.of("dir", Map.of("type", "string")))));
        tools.add(tool(
                "jk_deps",
                "Preview/apply surgical dependency edits (g:n:v). apply=false by default.",
                objectSchema(Map.of(
                        "action",
                        Map.of("type", "string", "description", "add | remove"),
                        "coords",
                        Map.of("type", "array", "items", Map.of("type", "string")),
                        "scope",
                        Map.of("type", "string", "description", "main|test|runtime|provided|processor"),
                        "apply",
                        Map.of("type", "boolean"),
                        "dir",
                        Map.of("type", "string")))));
        tools.add(tool(
                "jk_workspace",
                "Preview/apply workspace member add/remove. Distinct from jk_deps git.",
                objectSchema(Map.of(
                        "action",
                        Map.of("type", "string", "description", "add_member | remove_member"),
                        "path",
                        Map.of("type", "string"),
                        "apply",
                        Map.of("type", "boolean"),
                        "dir",
                        Map.of("type", "string")))));
        tools.add(tool(
                "jk_manifest",
                "Set whitelisted jk.toml keys. java=N is language level, not jdk=N.",
                objectSchema(Map.of(
                        "java",
                        Map.of("type", "integer"),
                        "apply",
                        Map.of("type", "boolean"),
                        "dir",
                        Map.of("type", "string")))));
        tools.add(tool(
                "jk_config",
                "get / set machine config, or apply_preset=ci.",
                objectSchema(Map.of(
                        "action",
                        Map.of("type", "string", "description", "get | set | apply_preset"),
                        "key",
                        Map.of("type", "string", "description", "nerd-font | engine.max-heap-mb"),
                        "value",
                        Map.of("type", "string"),
                        "preset",
                        Map.of("type", "string", "description", "ci")))));
        tools.add(tool(
                "jk_disk",
                "Cache vs store disk usage. clean/nuke require confirm=true (nuke cache only).",
                objectSchema(Map.of(
                        "action",
                        Map.of("type", "string", "description", "usage | clean | nuke"),
                        "confirm",
                        Map.of("type", "boolean")))));
        tools.add(tool(
                "jk_jdk",
                "List, install, or uninstall JDKs. uninstall and older_than require confirm=true.",
                objectSchema(Map.of(
                        "action",
                        Map.of("type", "string", "description", "list (default) | install | uninstall"),
                        "spec",
                        Map.of("type", "string", "description", "lts / latest / temurin-26 / 26"),
                        "older_than",
                        Map.of("type", "integer", "description", "uninstall jk-owned majors below this"),
                        "confirm",
                        Map.of("type", "boolean")))));
        tools.add(tool("jk_doctor", "Host health snapshot (config + disk).", objectSchema(Map.of())));
        return Map.of("tools", tools);
    }

    private Map<String, Object> toolsCall(Map<String, Object> params) {
        String name = string(params.get("name"));
        if (name == null || name.isBlank()) throw new McpError(-32602, "tools/call requires name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        String progressToken = progressTokenOf(params);

        return switch (name) {
            case "jk_status" -> {
                Map<String, Object> st = statusPayload();
                yield ok(st, statusSummary(st));
            }
            case "jk_build" ->
                ok(jobPayload(JobSpec.of("build", resolveDir(args, true)), progressToken), "build accepted");
            case "jk_test" ->
                ok(jobPayload(JobSpec.of("test", resolveDir(args, true)), progressToken), "test accepted");
            case "jk_lock" ->
                ok(jobPayload(JobSpec.of("lock", resolveDir(args, true)), progressToken), "lock accepted");
            case "jk_cancel" -> cancelResult(args);
            case "jk_bind" -> bindResult(args);
            case "jk_project" -> projectResult(args);
            case "jk_history" -> historyResult(args);
            case "jk_diagnostics" -> diagnosticsResult(args);
            case "jk_run" -> runResult(args, progressToken);
            case "jk_job" -> jobResult(args);
            case "jk_why" -> whyResult(args);
            case "jk_explain" -> explainResult(args);
            case "jk_outdated" -> outdatedResult(args);
            case "jk_deps" -> depsResult(args);
            case "jk_workspace" -> workspaceResult(args);
            case "jk_manifest" -> manifestResult(args);
            case "jk_config" -> configResult(args);
            case "jk_disk" -> diskResult(args);
            case "jk_jdk" -> jdkResult(args);
            case "jk_doctor" -> ok(McpEnvelope.of("doctor", McpMachine.doctor(cacheSnapshot)), "doctor");
            default -> throw new McpError(-32602, "unknown tool: " + name);
        };
    }

    private Map<String, Object> ok(Map<String, Object> envelope, String summary) {
        return McpEnvelope.toolResult(envelope, summary);
    }

    private static String statusSummary(Map<String, Object> status) {
        return "engine " + status.getOrDefault("version", "") + " pid " + status.getOrDefault("pid", "");
    }

    /**
     * MCP progress token from {@code params._meta.progressToken} (string or number). Numeric
     * tokens are canonicalized to their integral form — MiniJson parses numbers as Double, and
     * {@code String.valueOf(5.0)} would never match the client's {@code ?progressToken=5} query.
     */
    private static String progressTokenOf(Map<String, Object> params) {
        Object meta = params.get("_meta");
        if (!(meta instanceof Map<?, ?> m)) return null;
        Object tok = m.get("progressToken");
        if (tok == null) return null;
        String s = ProgressTokenRegistry.canonicalText(String.valueOf(tok));
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private Map<String, Object> statusPayload() {
        StatusSnapshot s = status.get();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", s.version());
        fields.put("pid", s.pid());
        fields.put("startedAt", s.startedAtMillis());
        fields.put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000));
        fields.put("activeRequests", s.activeRequests());
        fields.put("activeBuildPlans", s.activeBuildPlans());
        fields.put("heapUsedBytes", s.heapUsedBytes());
        fields.put("heapCommittedBytes", s.heapCommittedBytes());
        fields.put("heapMaxBytes", s.heapMaxBytes());
        fields.put("rssBytes", s.rssBytes());
        fields.put("cores", s.cores());
        String bound = session.dir();
        if (bound != null) fields.put("boundDir", bound);
        fields.put("jobs", liveJobRows());
        Map<String, Object> last = lastFinished(bound);
        if (last != null) fields.put("lastRun", last);
        return McpEnvelope.of("status", fields);
    }

    private List<Map<String, Object>> liveJobRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (HttpLive.Run r : liveRuns.get()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jid", r.requestId());
            row.put("kind", r.kind());
            row.put("dir", r.dir());
            row.put("progress", Double.isNaN(r.progress()) ? null : r.progress());
            // Stall is silence, not age: a healthy 10-minute build ticks progress the whole way.
            // Jobs that never emit a signal fall back to startedAt.
            long basis = r.lastEventAt() > 0 ? r.lastEventAt() : r.startedAt();
            long age = Math.max(0, now - basis);
            row.put("lastEventAgeMs", age);
            row.put("stalled", age >= STALL_MS && (Double.isNaN(r.progress()) || r.progress() < 100));
            out.add(row);
        }
        return out;
    }

    private @org.jspecify.annotations.Nullable Map<String, Object> lastFinished(String boundDir) {
        return summarizeJob(McpDiagnostics.findNewest(historyRaw.get(), boundDir));
    }

    private @org.jspecify.annotations.Nullable Map<String, Object> finishedJob(long jid, String dir, long triggeredAt) {
        Map<String, Object> rec = waitForJournal(jid);
        if (rec == null) {
            // Newest-row fallback covers records written without a requestId stamp. A row that
            // started before this job was triggered (delayed complete(), history disabled) is a
            // previous run's outcome and must not be attributed to this jid.
            Map<String, Object> newest = McpDiagnostics.findNewest(historyRaw.get(), dir);
            if (newest != null && McpHistoryViews.lng(newest, "startedAt") >= triggeredAt) rec = newest;
        }
        return summarizeJob(rec);
    }

    /**
     * Journal write races live-run teardown (HTTP jobs unregister before {@code writeJournal}).
     * Poll briefly for the finished row stamped with this jid — via {@link #finishedRecords}, so
     * each poll is a memoized single-record read, never a full journal re-scan.
     */
    private @org.jspecify.annotations.Nullable Map<String, Object> waitForJournal(long jid) {
        long deadline = System.currentTimeMillis() + 1_000;
        while (true) {
            String raw = finishedRecords.apply(jid);
            if (raw != null) return parseRecord(raw);
            if (System.currentTimeMillis() >= deadline) return null;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static @org.jspecify.annotations.Nullable Map<String, Object> summarizeJob(
            @org.jspecify.annotations.Nullable Map<String, Object> rec) {
        if (rec == null) return null;
        Map<String, Object> sum = McpHistoryViews.summarize(rec);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("id", sum.get("id"));
        one.put("kind", sum.get("kind"));
        one.put("success", sum.get("success"));
        one.put("exitCode", sum.get("exitCode"));
        one.put("failedModules", sum.get("failedModules"));
        if (sum.get("jid") != null) one.put("jid", sum.get("jid"));
        return one;
    }

    private Map<String, Object> jobPayload(JobSpec spec, String progressToken) {
        try {
            long requestId = jobs.trigger(spec);
            if (progressToken != null) progressTokens.bind(progressToken, requestId);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("kind", spec.kind());
            fields.put("jid", requestId);
            fields.put("dir", spec.dir());
            fields.put("events", "/api/events");
            fields.put("mcpEvents", "GET /mcp?jid=" + requestId);
            if (!spec.modules().isEmpty()) fields.put("modules", spec.modules());
            if (spec.hasTestFilter()) {
                fields.put("include_tags", spec.includeTags());
                fields.put("exclude_tags", spec.excludeTags());
                fields.put("suites", spec.suites());
            }
            if (progressToken != null) {
                fields.put("progressToken", progressToken);
                fields.put("mcpEventsByToken", "GET /mcp?progressToken=" + progressToken);
            }
            return McpEnvelope.of(
                    spec.kind() + "-accepted",
                    fields,
                    false,
                    null,
                    "Stream GET /mcp?jid=" + requestId + " or jk_cancel jid=" + requestId);
        } catch (IllegalStateException e) {
            throw new McpError(-32000, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
    }

    private Map<String, Object> cancelResult(Map<String, Object> args) {
        if (!(args.get("jid") instanceof Number n)) {
            String dir = string(args.get("dir"));
            if (dir != null && !dir.isBlank()) {
                int count = jobs.cancelDir(dir);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("dir", dir);
                fields.put("cancelled", count);
                return ok(
                        McpEnvelope.of("cancel", fields),
                        count > 0 ? "cancelled " + count + " job(s)" : "no running jobs for dir");
            }
            throw new McpError(-32602, "jk_cancel requires arguments.jid (or dir)");
        }
        long id = n.longValue();
        boolean ok = jobs.cancel(id);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jid", id);
        fields.put("cancelled", ok);
        if (!ok) fields.put("note", "unknown or already finished jid");
        return ok(McpEnvelope.of("cancel", fields), ok ? "cancelled " + id : "jid " + id + " not cancelled");
    }

    private Map<String, Object> bindResult(Map<String, Object> args) {
        String dir = string(args.get("dir"));
        if (dir == null || dir.isBlank()) throw new McpError(-32602, "jk_bind requires arguments.dir");
        String abs;
        try {
            abs = PathUtil.resolveUserPath(dir).toString();
        } catch (RuntimeException e) {
            throw new McpError(-32602, "invalid dir: " + e.getMessage());
        }
        session.bind(abs);
        Map<String, Object> card = McpProjectCards.card(abs, historyRaw.get());
        Map<String, Object> env = McpEnvelope.of("project", card, false, null, "jk_history for recent runs");
        String coord = String.valueOf(card.getOrDefault("coord", abs));
        return ok(env, "bound " + coord);
    }

    private Map<String, Object> projectResult(Map<String, Object> args) {
        String dir = resolveDir(args, true);
        String abs;
        try {
            abs = PathUtil.resolveUserPath(dir).toString();
        } catch (RuntimeException e) {
            abs = dir;
        }
        Map<String, Object> card = McpProjectCards.card(abs, historyRaw.get());
        // Keep legacy lookup keys (coord/description) if parse failed.
        if (!card.containsKey("coord")) {
            card.putAll(projectLookup.apply(dir));
        }
        return ok(McpEnvelope.of("project", card), String.valueOf(card.getOrDefault("coord", abs)));
    }

    private Map<String, Object> historyResult(Map<String, Object> args) {
        int limit = intArg(args.get("limit"), 10, 1, 200);
        int skip = intArg(args.get("next"), 0, 0, Integer.MAX_VALUE);
        String view = string(args.get("view"));
        boolean full = view != null && "full".equalsIgnoreCase(view);
        String dir = string(args.get("dir"));
        if (dir == null || dir.isBlank()) dir = session.dir();
        String projectId = string(args.get("projectId"));
        String kind = string(args.get("kind"));
        Boolean success = McpHistoryViews.parseBool(args.get("success"));

        List<String> raw = historyRaw.get();
        List<Object> matched = new ArrayList<>();
        for (String r : raw) {
            Map<String, Object> rec = parseRecord(r);
            if (rec == null) continue;
            if (!McpHistoryViews.matches(rec, dir, projectId, success, kind)) continue;
            matched.add(full ? rec : McpHistoryViews.summarize(rec));
        }
        int total = matched.size();
        int from = Math.min(skip, total);
        int to = Math.min(from + limit, total);
        List<Object> page = new ArrayList<>(matched.subList(from, to));
        boolean truncated = to < total;
        Object next = truncated ? Integer.valueOf(to) : null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("records", page);
        fields.put("count", page.size());
        fields.put("totalMatched", total);
        String hint = truncated ? "jk_history next=" + next : null;
        String summary = page.size() + " of " + total + " runs";
        return ok(McpEnvelope.of("history", fields, truncated, next, hint), summary);
    }

    private Map<String, Object> diagnosticsResult(Map<String, Object> args) {
        String dir = string(args.get("dir"));
        if (dir == null || dir.isBlank()) dir = session.dir();
        String run = string(args.get("run"));
        Map<String, Object> rec = McpDiagnostics.findRun(historyRaw.get(), run, dir);
        if (rec == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("diagnostics", List.of());
            empty.put("count", 0);
            return ok(McpEnvelope.of("diagnostics", empty, false, null, "no matching failed run"), "0 diagnostics");
        }
        boolean unique = !Boolean.FALSE.equals(McpHistoryViews.parseBool(args.get("unique")));
        String severity = string(args.get("severity"));
        String module = string(args.get("module"));
        List<Map<String, Object>> rows = McpDiagnostics.unique(McpDiagnostics.fromRecord(rec), unique);
        if (severity != null && !severity.isBlank()) {
            String sev = severity.toLowerCase();
            rows = rows.stream()
                    .filter(r -> sev.equalsIgnoreCase(String.valueOf(r.getOrDefault("severity", ""))))
                    .toList();
        }
        if (module != null && !module.isBlank()) {
            String needle = module.toLowerCase();
            rows = rows.stream()
                    .filter(r -> String.valueOf(r.getOrDefault("module", ""))
                                    .toLowerCase()
                                    .contains(needle)
                            || String.valueOf(r.getOrDefault("file", ""))
                                    .toLowerCase()
                                    .contains(needle))
                    .toList();
        }
        int limit = intArg(args.get("limit"), 20, 1, 200);
        int skip = intArg(args.get("next"), 0, 0, Integer.MAX_VALUE);
        int total = rows.size();
        int from = Math.min(skip, total);
        int to = Math.min(from + limit, total);
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(from, to));
        boolean truncated = to < total;
        Object next = truncated ? Integer.valueOf(to) : null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run", McpHistoryViews.str(rec, "id"));
        fields.put("diagnostics", page);
        fields.put("count", page.size());
        fields.put("totalMatched", total);
        String hint = truncated ? "jk_diagnostics next=" + next : "jk_run kind=build wait=true to rebuild";
        return ok(
                McpEnvelope.of("diagnostics", fields, truncated, next, hint),
                page.size() + " of " + total + " diagnostics");
    }

    private Map<String, Object> runResult(Map<String, Object> args, String progressToken) {
        if (args.containsKey("aot_cache")) {
            // Not hosted: rejecting beats a silent no-op an agent would read as AOT training.
            throw new McpError(-32602, "aot_cache is not supported; run jobs train AOT via engine policy");
        }
        String kind = string(args.get("kind"));
        if (kind == null || kind.isBlank()) kind = "build";
        JobSpec spec = new JobSpec(
                kind,
                resolveDir(args, true),
                stringList(args.get("modules")),
                stringList(args.get("include_tags")),
                stringList(args.get("exclude_tags")),
                stringList(args.get("suites")),
                Boolean.TRUE.equals(McpHistoryViews.parseBool(args.get("skip_tests"))));
        boolean wait = !Boolean.FALSE.equals(McpHistoryViews.parseBool(args.get("wait")));
        int timeoutS = intArg(args.get("timeout_s"), 600, 1, MAX_WAIT_S);
        long triggeredAt = System.currentTimeMillis();
        long jid = jobPayloadId(spec, progressToken);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", spec.kind());
        fields.put("jid", jid);
        fields.put("dir", spec.dir());
        if (!spec.modules().isEmpty()) fields.put("modules", spec.modules());
        if (spec.hasTestFilter()) {
            fields.put("include_tags", spec.includeTags());
            fields.put("exclude_tags", spec.excludeTags());
            fields.put("suites", spec.suites());
        }
        if (!wait) {
            fields.put("mcpEvents", "GET /mcp?jid=" + jid);
            return ok(
                    McpEnvelope.of("job-accepted", fields, false, null, "jk_job action=wait jid=" + jid), "jid " + jid);
        }
        // Parked waits yield their RPC admission permit — 16 waiting agents must not 503 the surface.
        boolean done = admissionYield.yielding(() -> waitUntilGone(jid, timeoutS * 1000L));
        fields.put("waited", true);
        fields.put("finished", done);
        if (!done) {
            return ok(
                    McpEnvelope.of("job", fields, false, null, "jk_job action=wait jid=" + jid),
                    "still running " + jid);
        }
        Map<String, Object> last =
                admissionYield.yielding(() -> finishedJob(jid, resolveDir(args, false), triggeredAt));
        if (last != null) {
            fields.put("result", last);
            if (Boolean.FALSE.equals(last.get("success"))) {
                Object runId = last.get("id");
                // The job's own dir, not the bound dir — the run may live outside the session.
                Map<String, Object> diags =
                        diagnosticsResult(runId == null ? Map.of() : Map.of("run", runId, "dir", spec.dir()));
                @SuppressWarnings("unchecked")
                Map<String, Object> env = (Map<String, Object>) diags.get("structuredContent");
                if (env != null) fields.put("diagnostics", env.get("diagnostics"));
            }
        }
        return ok(McpEnvelope.of("job", fields), done ? "finished " + jid : "jid " + jid);
    }

    private long jobPayloadId(JobSpec spec, String progressToken) {
        Map<String, Object> accepted = jobPayload(spec, progressToken);
        Object jid = accepted.get("jid");
        if (jid instanceof Number n) return n.longValue();
        throw new McpError(-32603, "job did not return jid");
    }

    private Map<String, Object> jobResult(Map<String, Object> args) {
        String action = string(args.get("action"));
        if (action == null || action.isBlank()) action = "get";
        action = action.toLowerCase(Locale.ROOT);
        Long jid = numberArg(args.get("jid"));
        if ("cancel".equals(action) && jid == null && session.dir() == null) {
            // Unbound sessions must name their victim: "latest live job" across every dir could
            // kill another client's build.
            throw new McpError(-32602, "jk_job cancel requires jid (or jk_bind first)");
        }
        if (jid == null) jid = latestLiveJid(session.dir());
        if ("cancel".equals(action)) {
            if (jid == null) throw new McpError(-32602, "no live job to cancel");
            boolean ok = jobs.cancel(jid);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("jid", jid);
            fields.put("cancelled", ok);
            return ok(McpEnvelope.of("cancel", fields), ok ? "cancelled " + jid : "jid " + jid + " not cancelled");
        }
        if (jid == null) {
            return ok(McpEnvelope.of("job", Map.of("live", false)), "no live job");
        }
        if ("wait".equals(action)) {
            int timeoutS = intArg(args.get("timeout_s"), 600, 1, MAX_WAIT_S);
            long waitJid = jid;
            boolean done = admissionYield.yielding(() -> waitUntilGone(waitJid, timeoutS * 1000L));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("jid", jid);
            fields.put("finished", done);
            return ok(McpEnvelope.of("job", fields), done ? "finished " + jid : "still running " + jid);
        }
        boolean live = isLive(jid);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jid", jid);
        fields.put("live", live);
        return ok(McpEnvelope.of("job", fields), live ? "running " + jid : "jid " + jid + " not live");
    }

    private boolean waitUntilGone(long jid, long timeoutMs) {
        long start = System.currentTimeMillis();
        boolean seen = false;
        while (System.currentTimeMillis() - start < timeoutMs) {
            boolean live = isLive(jid);
            if (live) seen = true;
            if (seen && !live) return true;
            if (!seen && System.currentTimeMillis() - start > 250) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isLive(jid);
    }

    private boolean isLive(long jid) {
        for (HttpLive.Run r : liveRuns.get()) {
            if (r.requestId() == jid) return true;
        }
        return false;
    }

    private Long latestLiveJid(String dir) {
        String want = dir == null ? null : McpHistoryViews.normalizeDir(dir);
        HttpLive.Run newest = null;
        for (HttpLive.Run r : liveRuns.get()) {
            if (want != null) {
                String have = McpHistoryViews.normalizeDir(r.dir() == null ? "" : r.dir());
                if (!have.equals(want) && !have.startsWith(want + "/")) continue;
            }
            // Newest by startedAt (jid tie-break) — the live-run snapshot iterates a hash map,
            // so list position is meaningless.
            if (newest == null
                    || r.startedAt() > newest.startedAt()
                    || (r.startedAt() == newest.startedAt() && r.requestId() > newest.requestId())) {
                newest = r;
            }
        }
        return newest == null ? null : newest.requestId();
    }

    private Map<String, Object> whyResult(Map<String, Object> args) {
        String query = string(args.get("query"));
        if (query == null || query.isBlank()) throw new McpError(-32602, "jk_why requires arguments.query");
        String dir = resolveDir(args, true);
        Map<String, Object> data = McpReads.why(dir, query);
        String summary = data.containsKey("error") ? String.valueOf(data.get("error")) : "why " + query;
        return ok(McpEnvelope.of("why", data), summary);
    }

    private Map<String, Object> explainResult(Map<String, Object> args) {
        String dir = resolveDir(args, true);
        Map<String, Object> data = McpReads.explain(dir);
        Object dirty = data.getOrDefault("dirtyCount", data.get("error"));
        return ok(McpEnvelope.of("explain", data), "explain dirty=" + dirty);
    }

    private Map<String, Object> outdatedResult(Map<String, Object> args) {
        String dir = resolveDir(args, true);
        Map<String, Object> data = McpReads.outdated(dir);
        Object n = data.get("rows") instanceof List<?> l ? l.size() : data.get("error");
        return ok(McpEnvelope.of("outdated", data), "outdated " + n);
    }

    private Map<String, Object> depsResult(Map<String, Object> args) {
        String dir = resolveDir(args, true);
        String action = string(args.get("action"));
        if (action == null) action = "add";
        List<String> coords = stringList(args.get("coords"));
        boolean apply = Boolean.TRUE.equals(McpHistoryViews.parseBool(args.get("apply")));
        Map<String, Object> data = McpManifest.deps(dir, action, coords, string(args.get("scope")), apply);
        return ok(McpEnvelope.of("deps", data, false, null, relockHint(data)), apply ? "deps applied" : "deps preview");
    }

    private Map<String, Object> workspaceResult(Map<String, Object> args) {
        String dir = resolveDir(args, true);
        String action = string(args.get("action"));
        if (action == null) action = "add_member";
        String path = string(args.get("path"));
        if (path == null || path.isBlank()) throw new McpError(-32602, "jk_workspace requires path");
        boolean apply = Boolean.TRUE.equals(McpHistoryViews.parseBool(args.get("apply")));
        Map<String, Object> data = McpManifest.workspace(dir, action, path, apply);
        return ok(
                McpEnvelope.of("workspace", data, false, null, relockHint(data)),
                apply ? "workspace applied" : "workspace preview");
    }

    private Map<String, Object> manifestResult(Map<String, Object> args) {
        String dir = resolveDir(args, true);
        Object javaRaw = args.get("java");
        if (!(javaRaw instanceof Number n)) throw new McpError(-32602, "jk_manifest requires java");
        boolean apply = Boolean.TRUE.equals(McpHistoryViews.parseBool(args.get("apply")));
        Map<String, Object> data = McpManifest.setJava(dir, n.intValue(), apply);
        return ok(McpEnvelope.of("manifest", data, false, null, relockHint(data)), "java=" + n.intValue());
    }

    /** Applied jk.toml edits stale {@code manifests-sha256}; the result must say how to re-lock. */
    private static String relockHint(Map<String, Object> data) {
        return Boolean.TRUE.equals(data.get("applied")) ? "jk_run kind=lock to refresh the stale jk-lock.toml" : null;
    }

    private Map<String, Object> configResult(Map<String, Object> args) {
        String action = string(args.get("action"));
        if (action == null || action.isBlank()) action = "get";
        // Only the explicit action mutates — `action=get preset=ci` is a read, never a write.
        if ("apply_preset".equals(action)) {
            return ok(McpEnvelope.of("config", McpMachine.applyCiPreset()), "ci preset");
        }
        if ("set".equals(action)) {
            String key = string(args.get("key"));
            String value = string(args.get("value"));
            if (key == null) throw new McpError(-32602, "jk_config set requires key");
            return ok(McpEnvelope.of("config", McpMachine.configSet(key, value)), "set " + key);
        }
        return ok(McpEnvelope.of("config", McpMachine.configGet()), "config");
    }

    private Map<String, Object> jdkResult(Map<String, Object> args) {
        String action = string(args.get("action"));
        if (action == null || action.isBlank()) action = "list";
        if ("list".equals(action)) {
            return ok(McpEnvelope.of("jdk", McpMachine.jdkList()), "jdk list");
        }
        Integer olderThan = null;
        Object raw = args.get("older_than");
        if (raw instanceof Number n) olderThan = n.intValue();
        boolean confirm = Boolean.TRUE.equals(McpHistoryViews.parseBool(args.get("confirm")));
        Map<String, Object> m = McpMachine.jdkAction(action, string(args.get("spec")), olderThan, confirm);
        String summary = m.containsKey("error")
                ? String.valueOf(m.get("error"))
                : Boolean.TRUE.equals(m.get("preview")) ? "confirm required" : action;
        return ok(McpEnvelope.of("jdk", m), summary);
    }

    private Map<String, Object> diskResult(Map<String, Object> args) {
        String action = string(args.get("action"));
        if (action == null || action.isBlank()) action = "usage";
        if ("usage".equals(action)) {
            return ok(McpEnvelope.of("disk", McpMachine.diskUsage(cacheSnapshot)), "disk usage");
        }
        boolean confirm = Boolean.TRUE.equals(McpHistoryViews.parseBool(args.get("confirm")));
        Map<String, Object> m = McpMachine.diskAction(action, confirm, cacheGate);
        if (confirm && cacheSnapshot instanceof CacheSnapshot.Memoizing memo) {
            memo.invalidate(); // clean/nuke moved bytes; the next read must re-walk
        }
        String summary = m.containsKey("error")
                ? String.valueOf(m.get("error"))
                : Boolean.TRUE.equals(m.get("preview")) ? "confirm required" : action;
        return ok(McpEnvelope.of("disk", m), summary);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static Map<String, Object> resourcesList() {
        List<Map<String, Object>> rs = new ArrayList<>();
        rs.add(resource("jk://session", "Bound dir + engine status"));
        rs.add(resource("jk://project", "Project card"));
        rs.add(resource("jk://runs/latest", "Latest history summary"));
        rs.add(resource("jk://disk", "Cache and store usage"));
        rs.add(resource("jk://config", "Effective machine config"));
        return Map.of("resources", rs);
    }

    private Map<String, Object> resourcesRead(Map<String, Object> params) {
        String uri = string(params.get("uri"));
        if (uri == null) throw new McpError(-32602, "resources/read requires uri");
        Map<String, Object> payload =
                switch (uri) {
                    case "jk://session" -> statusPayload();
                    case "jk://project" -> {
                        String dir = session.dir();
                        if (dir == null) yield Map.of("error", "jk_bind first");
                        yield McpProjectCards.card(dir, historyRaw.get());
                    }
                    case "jk://runs/latest" -> {
                        // Newest finished record — skips corrupt rows and running stubs instead
                        // of NPEing on parseRecord(null).
                        Map<String, Object> rec = McpDiagnostics.findNewest(historyRaw.get(), null);
                        yield rec == null
                                ? Map.of("records", List.of())
                                : Map.of("record", McpHistoryViews.summarize(rec));
                    }
                    case "jk://disk" -> McpMachine.diskUsage(cacheSnapshot);
                    case "jk://config" -> McpMachine.configGet();
                    default -> throw new McpError(-32602, "unknown resource: " + uri);
                };
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("uri", uri);
        text.put("mimeType", "application/json");
        text.put("text", MiniJson.write(payload));
        return Map.of("contents", List.of(text));
    }

    /** Prompt name → one-line playbook; drives both {@code prompts/list} and {@code prompts/get}. */
    private static final Map<String, String> PROMPTS = promptCatalog();

    private static Map<String, String> promptCatalog() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fix-failing-build", "jk_diagnostics then edit then jk_run kind=build wait=true");
        m.put("recover-disk", "jk_disk usage then clean or nuke with confirm");
        m.put("setup-ci", "jk_config apply_preset=ci");
        m.put("upgrade-deps", "jk_outdated then jk_run kind=lock");
        m.put("stall-or-cancel", "jk_status then jk_job cancel");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> promptsList() {
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Map.Entry<String, String> e : PROMPTS.entrySet()) {
            ps.add(prompt(e.getKey(), e.getValue()));
        }
        return Map.of("prompts", ps);
    }

    private static Map<String, Object> promptsGet(Map<String, Object> params) {
        String name = string(params.get("name"));
        if (name == null || name.isBlank()) throw new McpError(-32602, "prompts/get requires name");
        String description = PROMPTS.get(name);
        if (description == null) throw new McpError(-32602, "unknown prompt: " + name);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", description);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", description);
        result.put("messages", List.of(message));
        return result;
    }

    private static Map<String, Object> resource(String uri, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", uri);
        m.put("name", uri);
        m.put("description", description);
        m.put("mimeType", "application/json");
        return m;
    }

    private static Map<String, Object> prompt(String name, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        return m;
    }

    private static Long numberArg(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRecord(String raw) {
        try {
            Object o = MiniJson.parse(raw);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String resolveDir(Map<String, Object> args, boolean required) {
        String dir = string(args.get("dir"));
        if (dir == null || dir.isBlank()) dir = session.dir();
        if ((dir == null || dir.isBlank()) && required) {
            throw new McpError(-32602, "requires arguments.dir (or jk_bind first)");
        }
        return dir;
    }

    private static int intArg(Object raw, int fallback, int min, int max) {
        int n = fallback;
        if (raw instanceof Number num) n = num.intValue();
        else if (raw != null) {
            try {
                n = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                n = fallback;
            }
        }
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        return t;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return objectSchema(properties, List.of());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) schema.put("required", required);
        return schema;
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
