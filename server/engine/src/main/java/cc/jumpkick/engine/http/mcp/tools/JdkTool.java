// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jdk} — list, install or uninstall jk-owned JDKs. Removals need {@code confirm}. */
public final class JdkTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jdk",
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
        boolean confirm = in.flag("confirm");
        if (confirm && "uninstall".equals(action)) {
            Integer older = in.intOrNull("older_than");
            String what = older != null ? "every jk-owned JDK below " + older : "JDK " + in.str("spec");
            Map<String, Object> stop =
                    McpDecisions.confirm(in, this, "jdk-uninstall", "Uninstall " + what + "?", "Yes, uninstall it");
            if (stop != null) return stop;
        }
        Map<String, Object> data = McpMachine.jdkAction(action, in.str("spec"), in.intOrNull("older_than"), confirm);
        return in.ok(McpEnvelope.of("jdk", data), MachineActions.summary(data, action));
    }
}
