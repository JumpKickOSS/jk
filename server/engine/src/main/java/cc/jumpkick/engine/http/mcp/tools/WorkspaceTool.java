// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_workspace} — preview or apply a workspace member add/remove. */
public final class WorkspaceTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_workspace",
                "Preview/apply workspace member add/remove. Distinct from jk_deps git.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("add_member | remove_member"),
                        "path",
                        McpSchemas.string(),
                        "apply",
                        McpSchemas.bool(),
                        "dir",
                        McpSchemas.string())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String dir = in.requiredDir();
        String path = in.str("path");
        if (path == null || path.isBlank()) throw new McpError(-32602, "jk_workspace requires path");
        boolean apply = in.flag("apply");
        Map<String, Object> data = McpManifest.workspace(dir, in.action("add_member"), path, apply);
        return in.ok(
                McpEnvelope.of("workspace", data, false, null, ManifestEdits.relockHint(data)),
                apply ? "workspace applied" : "workspace preview");
    }
}
