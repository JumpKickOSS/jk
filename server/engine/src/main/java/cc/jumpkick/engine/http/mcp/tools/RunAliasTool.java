// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/**
 * {@code jk_publish} / {@code jk_import} — a discoverable name for one {@code jk_run} kind. The
 * body is {@code jk_run}'s, pinned: a second job path would be a second definition of "finished".
 */
public final class RunAliasTool implements McpTool {

    private final Spec spec;
    private final String kind;

    public RunAliasTool(String name, String kind, String description, String dirDescription) {
        this.kind = kind;
        this.spec = new Spec(
                name,
                description,
                McpSchemas.object(Map.of(
                        "dir",
                        McpSchemas.string(dirDescription),
                        "wait",
                        McpSchemas.bool("Block until finish (default true)"))));
    }

    @Override
    public Spec spec() {
        return spec;
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return McpJobRuns.run(in, kind);
    }
}
