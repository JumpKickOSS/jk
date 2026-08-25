// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDetails;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_details} — a budgeted tail of one run's details.jsonl transcript. */
public final class DetailsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_details",
                "Budgeted tail of a run's details.jsonl transcript (default: last-fail, error + "
                        + "task-finish, 80 events). Same facts as CLI `jk results --details`, which "
                        + "prints the full file. Start with jk_results. Resource: jk://runs/latest/details.",
                McpSchemas.object(Map.of(
                        "run",
                        McpSchemas.string("last-fail (default) or history id"),
                        "tail",
                        McpSchemas.integer("Max events (default 80, max 400)"),
                        "types",
                        McpSchemas.strings("Event types (default error, task-finish)"),
                        "next",
                        McpSchemas.integer("Cursor from a prior truncated call"),
                        "dir",
                        McpSchemas.string(McpSchemas.CHECKOUT_FILTER))),
                McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> rec = McpDiagnostics.findRun(in.ctx().history(), in.str("run"), in.dir());
        Map<String, Object> fields = McpDetails.tail(
                rec,
                in.ctx().detailsFile(),
                in.strings("types"),
                in.count("tail", McpDetails.DEFAULT_TAIL, 1, McpDetails.MAX_TAIL),
                in.count("next", 0, 0, Integer.MAX_VALUE));
        boolean truncated = Boolean.TRUE.equals(fields.remove("truncatedTail"));
        Object next = fields.remove("nextCursor");
        return in.ok(
                McpEnvelope.of(
                        "details",
                        fields,
                        truncated,
                        next,
                        "jk_results is the high-level report; this is the raw transcript"),
                "details " + fields.getOrDefault("run", ""));
    }
}
