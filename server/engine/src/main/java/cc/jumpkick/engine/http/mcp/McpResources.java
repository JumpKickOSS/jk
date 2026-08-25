// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.docs.JkManual;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code resources/*} surface: the same facts as the read tools, addressable by {@code jk://}
 * URI for clients that browse rather than call. A resource that answers differently from its tool
 * is a second semantics for one domain fact, so each one delegates to the tool's owner.
 */
public final class McpResources {

    private McpResources() {}

    public static Map<String, Object> list() {
        List<Map<String, Object>> rs = new ArrayList<>();
        rs.add(resource("jk://manual", "JumpKick playbook (same as CLI jk manual / tool jk_manual)", "text/markdown"));
        rs.add(resource("jk://session", "Bound dir + engine status"));
        rs.add(resource("jk://project", "Project card"));
        rs.add(resource("jk://runs/latest", "Latest history summary"));
        rs.add(resource(
                "jk://runs/latest/results",
                "Latest run markdown (jk-results.md; same as jk_results / CLI jk results)",
                "text/markdown"));
        rs.add(resource(
                "jk://runs/latest/details",
                "Budgeted tail of latest details.jsonl (same as jk_details; CLI jk results --details dumps the full file)",
                "application/json"));
        rs.add(resource("jk://disk", "Cache and store usage"));
        rs.add(resource("jk://config", "Effective machine config"));
        return Map.of("resources", rs);
    }

    public static Map<String, Object> read(McpContext ctx, Map<String, Object> params) {
        Object raw = params.get("uri");
        String uri = raw == null ? null : String.valueOf(raw);
        if (uri == null) throw new McpError(-32602, "resources/read requires uri");
        if ("jk://manual".equals(uri)) return manualResource();
        if ("jk://runs/latest/results".equals(uri)) return resultsResource(ctx);
        if ("jk://runs/latest/details".equals(uri)) return detailsResource(ctx);
        Map<String, Object> payload =
                switch (uri) {
                    case "jk://session" -> McpVitals.statusPayload(ctx);
                    case "jk://project" -> {
                        String dir = ctx.session().dir();
                        if (dir == null) yield Map.of("error", "jk_bind first");
                        yield McpProjectCards.card(dir, ctx.history());
                    }
                    case "jk://runs/latest" -> {
                        // Newest finished record — skips corrupt rows and running stubs instead
                        // of NPEing on parseRecord(null).
                        Map<String, Object> rec = McpDiagnostics.findNewest(ctx.history(), null);
                        yield rec == null
                                ? Map.of("records", List.of())
                                : Map.of("record", McpHistoryViews.summarize(rec));
                    }
                    case "jk://disk" -> McpMachine.diskUsage(ctx.cacheSnapshot());
                    case "jk://config" -> McpMachine.configGet();
                    default -> throw new McpError(-32602, "unknown resource: " + uri);
                };
        return contents(uri, "application/json", MiniJson.write(payload));
    }

    private static Map<String, Object> manualResource() {
        return contents("jk://manual", "text/markdown", JkManual.markdown());
    }

    private static Map<String, Object> resultsResource(McpContext ctx) {
        Map<String, Object> rec =
                McpDiagnostics.findNewest(ctx.history(), ctx.session().dir());
        String id = rec == null ? null : McpHistoryViews.str(rec, "id");
        Path file = McpResults.locate(rec, id, ctx.detailsFile());
        if (file == null || !Files.isRegularFile(file)) {
            return contents(
                    "jk://runs/latest/results",
                    "application/json",
                    MiniJson.write(Map.of("error", "no jk-results.md")));
        }
        try {
            return contents(
                    "jk://runs/latest/results", "text/markdown", Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "read failed" : e.getMessage();
            return contents("jk://runs/latest/results", "application/json", MiniJson.write(Map.of("error", msg)));
        }
    }

    private static Map<String, Object> detailsResource(McpContext ctx) {
        Map<String, Object> rec =
                McpDiagnostics.findNewest(ctx.history(), ctx.session().dir());
        Map<String, Object> fields = McpDetails.tail(rec, ctx.detailsFile(), List.of(), McpDetails.DEFAULT_TAIL, 0);
        return contents("jk://runs/latest/details", "application/json", MiniJson.write(fields));
    }

    private static Map<String, Object> contents(String uri, String mimeType, String text) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("uri", uri);
        one.put("mimeType", mimeType);
        one.put("text", text);
        return Map.of("contents", List.of(one));
    }

    private static Map<String, Object> resource(String uri, String description) {
        return resource(uri, description, "application/json");
    }

    private static Map<String, Object> resource(String uri, String description, String mimeType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", uri);
        m.put("name", uri);
        m.put("description", description);
        m.put("mimeType", mimeType);
        return m;
    }
}
