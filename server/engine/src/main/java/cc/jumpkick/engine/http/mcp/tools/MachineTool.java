// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.machine.MachineEnvironments;
import java.util.List;
import java.util.Map;

/**
 * {@code machine} — the Windows engine's environments: {@code list} the host and each WSL distro with
 * the JumpKick it has; {@code install} or {@code remove} JumpKick in the distros {@code distros}
 * names. Installing or removing always asks the user first, through the decision queue, whatever the
 * call says: it is the dashboard's Install and Remove and {@code jk self setup-wsl}, one operation,
 * and an agent never does it on its own word. Only distros WSL lists can be named.
 */
public final class MachineTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "machine",
                "Windows only: list (default) the host and its WSL distros with their JumpKick state; install or remove"
                        + " JumpKick in the named distros (the user is asked first; progress shows in list).",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.oneOf("list", "install", "remove"),
                        "distros",
                        McpSchemas.strings("WSL distro names, as list shows them"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        MachineEnvironments machine = in.ctx().machine();
        if (machine == null) {
            return in.text("nothing to set up: the machine's environments are the Windows engine's\n");
        }
        String action = in.action("list");
        if ("list".equals(action)) return in.ok(McpEnvelope.of("machine", machine.list()), "machine list");
        MachineEnvironments.Action op =
                switch (action) {
                    case "install" -> MachineEnvironments.Action.INSTALL;
                    case "remove" -> MachineEnvironments.Action.REMOVE;
                    default -> null;
                };
        if (op == null) return in.error("action is list, install or remove\n");
        List<String> distros = in.strings("distros");
        if (distros.isEmpty()) return in.error("name the distros to " + action + " (list shows them)\n");
        String what = (op == MachineEnvironments.Action.INSTALL ? "Install JumpKick in " : "Remove JumpKick from ")
                + String.join(", ", distros) + "?";
        McpDecisions.Ask ask = new McpDecisions.Ask(
                "machine-" + action,
                what,
                List.of(
                        op == MachineEnvironments.Action.INSTALL
                                ? "Each distro gets the JumpKick release that matches JumpKick for Windows, and pairs with it."
                                : "Each distro's engines stop and its ~/.jk goes; Windows keeps its own JumpKick."),
                op == MachineEnvironments.Action.INSTALL ? "Yes, install" : "Yes, remove",
                List.of(),
                true);
        switch (McpDecisions.require(in, this, ask)) {
            case McpDecisions.Outcome.Stop s -> {
                return s.result();
            }
            case McpDecisions.Outcome.Proceed p -> {}
        }
        MachineEnvironments.Started started = machine.start(op, distros);
        return in.ok(
                McpEnvelope.of("machine", started.toMap()),
                started.accepted().isEmpty() ? "nothing started" : "machine " + action);
    }
}
