// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code jk node list} — the Node.js installs on this machine, the project's marked. */
public final class NodeListCommand implements CliCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public List<String> aliases() {
        return List.of("ls");
    }

    @Override
    public String description() {
        return "List installed Node.js versions, jk's and other managers'";
    }

    @Override
    public int run(Invocation in) throws IOException {
        boolean json = GlobalOptions.outputIsJson(in);
        List<NodeInstalls.Install> installs = NodeCli.installs().all();
        Optional<NodeInstalls.Install> current = Optional.empty();
        Optional<NodeInstalls.ProjectNode> project =
                NodeInstalls.project(GlobalOptions.from(in).workingDir());
        if (project.isPresent() && project.get().lookup() != null)
            current = NodeCli.installed(project.get().lookup());
        if (json) {
            for (NodeInstalls.Install i : installs) {
                boolean mark = current.isPresent() && current.get().equals(i);
                NodeCli.row(EngineProtocol.NODE_LIST, f -> f.string("version", i.version())
                        .string("source", i.source())
                        .string("home", i.home().toString())
                        .bool("current", mark));
            }
            return 0;
        }
        if (installs.isEmpty()) {
            CommandWedge.printOk(NodeCli.WEDGE, "No Node.js installed. Try `jk node install lts`.");
            return 0;
        }
        List<List<String>> rows = new ArrayList<>();
        for (NodeInstalls.Install i : installs) {
            boolean mark = current.isPresent() && current.get().equals(i);
            rows.add(List.of(mark ? "*" : "", i.version(), i.source(), i.home().toString()));
        }
        CommandWedge.envelopeStart();
        for (String line : Table.render("Node.js", List.of("", "Version", "Source", "Home"), rows)) {
            CliOutput.out(line);
        }
        return 0;
    }
}
