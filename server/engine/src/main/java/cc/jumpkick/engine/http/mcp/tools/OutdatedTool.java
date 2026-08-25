// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.List;
import java.util.Map;

/** {@code jk_outdated} — declared dependencies newer than the lock. */
public final class OutdatedTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_outdated",
                "Declared deps newer than the lock (current / compatible / latest). Read-only.",
                McpSchemas.object(Map.of("dir", McpSchemas.string())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> data = McpReads.outdated(in.requiredDir());
        Object rows = data.get("rows") instanceof List<?> l ? Integer.valueOf(l.size()) : data.get("error");
        return in.ok(McpEnvelope.of("outdated", data), "outdated " + rows);
    }
}
