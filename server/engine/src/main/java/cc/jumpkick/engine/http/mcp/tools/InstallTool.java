// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.runtime.base.ProjectInfoPlans;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code install} — install the project into the local Maven repo, or list the installed jkx
 * tools. Installing a tool stays CLI-side: the trust gate is a human decision.
 */
public final class InstallTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "install",
                "Install an APPLICATION or tool on this machine (jk install). A library is not installed here: "
                        + "use snapshot while iterating (version-SNAPSHOT, replaced each run) and jk release "
                        + "(CLI) to cut a version. action=list shows installed jkx tools; tool installs stay "
                        + "CLI-side (trust gates).",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("install (default) | list"),
                        "dir",
                        McpSchemas.string(McpSchemas.BOUND_ROOT),
                        "wait",
                        McpSchemas.bool("Block until finish (default true)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        if ("list".equalsIgnoreCase(in.str("action"))) {
            return in.ok(McpEnvelope.of("tools", McpMachine.tools()), "installed tools");
        }
        ProjectInfo info = ProjectInfoPlans.projectInfo(Path.of(in.requiredDir()));
        boolean installable = info.workspaceRoot()
                || info.application()
                || !info.productLib().isEmpty()
                || !info.productBin().isEmpty();
        if (info.error() == null && !installable) {
            return in.error(info.coord() + " is a library: install is for applications and tools. Use snapshot to "
                    + "install it for sibling projects while you edit, and `jk release` on the CLI to cut a version.");
        }
        return McpJobRuns.run(in, "install");
    }
}
