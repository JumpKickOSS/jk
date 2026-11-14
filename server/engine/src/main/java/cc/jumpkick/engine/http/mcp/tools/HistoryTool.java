// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code history} — recent runs as summaries, a page at a time. It reads records only until the
 * page is full; {@code details} and {@code diagnostics} carry what a raw record would.
 */
public final class HistoryTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "history",
                "Recent runs as summaries (id, success, failed modules, diagnostic count). "
                        + "Filters: dir, projectId, success, kind, limit, next. "
                        + "details and diagnostics read one run in depth.",
                McpSchemas.object(Map.of(
                        "limit",
                        McpSchemas.integer("Max rows (default 10, max 200)"),
                        "next",
                        McpSchemas.integer("Skip this many matching rows (from prior next)"),
                        "dir",
                        McpSchemas.string("Filter to this checkout (default: bound dir)"),
                        "projectId",
                        McpSchemas.string("Filter to this durable project id"),
                        "kind",
                        McpSchemas.string("build | test | lock | …"),
                        "success",
                        McpSchemas.bool("Only successful or only failed runs"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        int limit = in.count("limit", 10, 1, 200);
        int skip = in.count("next", 0, 0, Integer.MAX_VALUE);
        String dir = in.dir();
        String projectId = in.str("projectId");
        String kind = in.str("kind");
        Boolean success = in.tri("success");

        List<String> history = in.ctx().history();
        List<Object> page = new ArrayList<>();
        int matched = 0;
        int scanned = 0;
        for (int i = 0; i < history.size() && page.size() < limit; i++) {
            scanned++;
            Map<String, Object> rec = McpHistoryViews.parseRecord(history.get(i));
            if (rec == null) continue;
            if (!McpHistoryViews.matches(rec, dir, projectId, success, kind)) continue;
            if (matched++ >= skip) page.add(McpHistoryViews.summarize(rec));
        }
        // More rows are left unread: offer the next page without reading them to count matches.
        boolean truncated = page.size() >= limit && scanned < history.size();
        Object next = truncated ? Integer.valueOf(skip + page.size()) : null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("records", page);
        fields.put("count", page.size());
        String hint = truncated ? "history next=" + next : null;
        return in.ok(
                McpEnvelope.of("history", fields, truncated, next, hint),
                page.size() + (truncated ? " runs, more with next" : " runs"));
    }
}
