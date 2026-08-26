// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_jdk} — list, install or uninstall jk-owned JDKs. Removals need {@code confirm}. */
public final class JdkTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_jdk",
                "List, install, or uninstall JDKs. uninstall and older_than require confirm=true.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("list (default) | install | uninstall"),
                        "spec",
                        McpSchemas.string("lts / latest / temurin-26 / 26"),
                        "older_than",
                        McpSchemas.integer("uninstall jk-owned majors below this"),
                        "confirm",
                        McpSchemas.bool())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("list");
        if ("list".equals(action)) {
            return in.ok(McpEnvelope.of("jdk", McpMachine.jdkList()), "jdk list");
        }
        Map<String, Object> data =
                McpMachine.jdkAction(action, in.str("spec"), in.intOrNull("older_than"), in.flag("confirm"));
        return in.ok(McpEnvelope.of("jdk", data), MachineActions.summary(data, action));
    }
}
