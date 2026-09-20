// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.List;
import java.util.Map;

/**
 * {@code jk_outdated} — declared dependencies an update would move; {@code all} lists every row.
 * Writes {@code target/jk-outdated-dependencies.md} and returns its path as {@code file}.
 */
public final class OutdatedTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_outdated",
                "Declared deps an update would move (current / compatible / latest), read-only; all=true lists"
                        + " every row checked, and file is the target/jk-outdated-dependencies.md it wrote",
                McpSchemas.object(
                        Map.of("dir", McpSchemas.string(), "all", McpSchemas.bool("Every row, up to date included"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> data = McpReads.outdated(in.requiredDir(), in.flag("all"));
        Object rows = data.get("rows") instanceof List<?> l ? Integer.valueOf(l.size()) : data.get("error");
        return in.ok(McpEnvelope.of("outdated", data), "outdated " + rows);
    }
}
