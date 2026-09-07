// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpPrompts;
import cc.jumpkick.engine.http.mcp.McpResources;
import cc.jumpkick.engine.http.mcp.McpRpc;
import cc.jumpkick.engine.http.mcp.McpTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>This class is the method table and nothing else. Framing is {@link McpRpc}, the tool set is
 * {@link McpTools#standard()}, and every collaborator a tool reaches hangs off {@link McpContext}.
 */
public final class McpHandler {

    /** MCP protocol version we advertise (Streamable-HTTP era baseline). */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    public static final String SERVER_NAME = "jk-engine";

    private final McpContext ctx;
    private final McpTools tools = McpTools.standard();

    /** The minimum wiring: no progress tokens, no live-run feed, no admission yield. */
    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            String version) {
        this(status, jobs, projectLookup, historyRaw, version, null, null, null, null);
    }

    /** Full wiring, as the embedded server builds it. Any optional collaborator may be null. */
    public McpHandler(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            @Nullable String version,
            @Nullable ProgressTokenRegistry progressTokens,
            @Nullable Supplier<List<HttpLive.Run>> liveRuns,
            @Nullable AdmissionYield admissionYield,
            @Nullable LongFunction<String> finishedRecords) {
        this.ctx = new McpContext(
                status,
                jobs,
                projectLookup,
                historyRaw,
                version,
                progressTokens,
                liveRuns,
                admissionYield,
                finishedRecords);
    }

    /** Wire the journal's {@code details.jsonl} locator for {@code jk_details}. Optional. */
    public void detailsFile(Function<String, Optional<Path>> resolver) {
        if (resolver != null) ctx.detailsFile(resolver);
    }

    /** Wire the shared cache/store snapshot supplier (memoized in the live engine). Optional. */
    public void cacheSnapshot(Supplier<CacheSnapshot> cacheSnapshot) {
        ctx.cacheSnapshot(cacheSnapshot);
    }

    /** Wire the engine's cache maintenance gate so destructive disk tools take the real locks. */
    public void cacheGate(ReentrantReadWriteLock cacheGate) {
        ctx.cacheGate(cacheGate);
    }

    /** Shrink the journal-write settle budget; tests only. */
    void journalSettleMs(long ms) {
        ctx.journalSettleMs(ms);
    }

    /**
     * Handle one HTTP body (JSON-RPC request or batch). Returns the JSON response body (object or
     * array). Notifications ({@code id} absent) yield an empty string — caller should respond 202
     * with no body or {@code {}}.
     */
    public String handleBody(String body) {
        return McpRpc.handleBody(body, this::route);
    }

    /**
     * The whole method table. Every arm here must appear in {@link McpRpc#initialize}'s
     * capabilities, and every capability there must have an arm here.
     */
    private @Nullable Object route(String method, Map<String, Object> params) {
        return switch (method) {
            case "initialize" -> McpRpc.initialize(PROTOCOL_VERSION, SERVER_NAME, ctx.version());
            case "notifications/initialized", "initialized" -> null; // notification
            case "ping" -> Map.of();
            case "tools/list" -> tools.listing();
            case "tools/call" -> tools.call(ctx, params);
            case "resources/list" -> McpResources.list();
            case "resources/read" -> McpResources.read(ctx, params);
            case "prompts/list" -> McpPrompts.list();
            case "prompts/get" -> McpPrompts.get(params);
            case "logging/setLevel" -> Map.of(); // declared capability; engine log level is fixed
            default -> throw new McpError(-32601, "method not found: " + method);
        };
    }
}
