// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.verbs.ManifestEdit;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import java.util.List;
import java.util.Map;

/** {@code workspace} — preview or apply a workspace member add/remove. */
public final class WorkspaceTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "workspace",
                "Preview/apply workspace member add/remove. Distinct from deps git.",
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
        if (path == null || path.isBlank()) throw new McpError(-32602, "workspace requires path");
        boolean apply = in.flag("apply");
        ManifestEdit.Op op = "remove_member".equals(in.action("add_member"))
                ? new ManifestEdit.Op.RemoveWorkspaceModule(path)
                : new ManifestEdit.Op.RegisterWorkspaceModule(path);
        Map<String, Object> data = ManifestEdit.structured(ManifestEdit.run(new ManifestEdit.Request(
                ManifestPaths.manifestIn(PathUtil.resolveUserPath(dir)), List.of(op), !apply, false)));
        return in.ok(
                McpEnvelope.of("workspace", data, false, null, ManifestEdits.relockHint(data)),
                apply ? "workspace applied" : "workspace preview");
    }
}
