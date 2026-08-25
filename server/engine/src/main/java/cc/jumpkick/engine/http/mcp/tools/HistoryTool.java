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

/** {@code jk_history} — recent runs as budgeted summaries; {@code view=full} is the raw journal. */
public final class HistoryTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_history",
                "Recent runs as summaries (id, success, failed modules, diagnostic count). "
                        + "Default view=summary. Filters: dir, projectId, success, kind, limit, next.",
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
                        McpSchemas.bool("Only successful or only failed runs"),
                        "view",
                        McpSchemas.string("summary (default) or full (raw journal — avoid)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        int limit = in.count("limit", 10, 1, 200);
        int skip = in.count("next", 0, 0, Integer.MAX_VALUE);
        String view = in.str("view");
        boolean full = "full".equalsIgnoreCase(view);
        String dir = in.dir();
        String projectId = in.str("projectId");
        String kind = in.str("kind");
        Boolean success = in.tri("success");

        List<Object> matched = new ArrayList<>();
        for (String raw : in.ctx().history()) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
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
        return in.ok(McpEnvelope.of("history", fields, truncated, next, hint), page.size() + " of " + total + " runs");
    }
}
