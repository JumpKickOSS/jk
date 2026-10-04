// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk node which} (also {@code home}) — the Node.js the project resolves to: the lock's
 * version, else the newest install satisfying the manifest's spec. Prints its {@code node}.
 */
public final class NodeWhichCommand implements CliCommand {

    @Override
    public String name() {
        return "which";
    }

    @Override
    public List<String> aliases() {
        return List.of("home");
    }

    @Override
    public String description() {
        return "Print the Node.js the project uses";
    }

    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path dir = GlobalOptions.from(in).workingDir();
        Optional<NodeInstalls.ProjectNode> project = NodeInstalls.project(dir);
        if (project.isEmpty() || project.get().lookup() == null) {
            CommandWedge.printFail(
                    NodeCli.WEDGE,
                    "no Node.js declared for " + PathDisplay.styledRaw(dir) + " (write one with `jk node pin`)");
            return Exit.CONFIG;
        }
        Optional<NodeInstalls.Install> install = NodeCli.installed(project.get().lookup());
        if (install.isEmpty()) {
            String want = project.get().wanted();
            CommandWedge.printFail(
                    NodeCli.WEDGE, "Node.js " + want + " is not installed — run `jk node install " + want + "`");
            return Exit.CONFIG;
        }
        NodeInstalls.Install i = install.get();
        Path node = new NodeHome(i.home(), i.version(), i.source(), null).node();
        if (GlobalOptions.outputIsJson(in)) {
            NodeCli.row(EngineProtocol.NODE_WHICH, f -> f.string("version", i.version())
                    .string("node", node.toString())
                    .string("home", i.home().toString())
                    .string("source", i.source()));
            return 0;
        }
        CliOutput.out(node.toString());
        return 0;
    }
}
