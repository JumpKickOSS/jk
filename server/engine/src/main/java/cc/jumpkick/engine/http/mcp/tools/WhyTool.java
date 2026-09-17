// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.List;
import java.util.Map;

/** {@code jk_why} — why one dependency is on the graph. */
public final class WhyTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_why",
                "Why a dependency is on the graph (matches with their paths, plus an exclusions array of the edges the manifest or a POM pruned); query is group, artifact, or substring.",
                McpSchemas.object(
                        Map.of("query", McpSchemas.string("group, artifact, or substring"), "dir", McpSchemas.string()),
                        List.of("query")));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String query = in.str("query");
        if (query == null || query.isBlank()) throw new McpError(-32602, "jk_why requires arguments.query");
        Map<String, Object> data = McpReads.why(in.requiredDir(), query);
        String summary = data.containsKey("error") ? String.valueOf(data.get("error")) : "why " + query;
        return in.ok(McpEnvelope.of("why", data), summary);
    }
}
