// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code graph} — the compact module/dependency graph the dashboard draws. */
public final class GraphTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "graph",
                "Compact module/dep graph (same model as the dashboard graph). Default: workspace "
                        + "members + declared deps. transitive=true is opt-in and budget-capped. "
                        + "For one artifact's origin prefer why.",
                McpSchemas.object(Map.of(
                        "dir",
                        McpSchemas.string(McpSchemas.BOUND_ROOT),
                        "scopes",
                        McpSchemas.string("CSV scopes (default export,main,runtime)"),
                        "transitive",
                        McpSchemas.bool("Expand lockfile closure (default false)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> graph = McpReads.graph(in.requiredDir(), in.str("scopes"), in.flag("transitive"));
        return in.ok(McpEnvelope.of("graph", graph), "graph");
    }
}
