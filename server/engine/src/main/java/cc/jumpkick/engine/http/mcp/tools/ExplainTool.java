// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code explain} — the next build's forecast: what is dirty and what the cache already has. */
public final class ExplainTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "explain",
                "Forecast the next build: dirty modules and cache hits (jk explain).",
                McpSchemas.object(Map.of("dir", McpSchemas.string())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> data = McpReads.explain(in.requiredDir());
        Object dirty = data.getOrDefault("dirtyCount", data.get("error"));
        return in.ok(McpEnvelope.of("explain", data), "explain dirty=" + dirty);
    }
}
