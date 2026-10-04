// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.node.NodeHome;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk node exec -- <cmd…>} — run a command with the project's Node.js and package manager
 * first on {@code PATH}; {@code --node <spec>} runs it under another Node.js once.
 */
public final class NodeExecCommand implements CliCommand {

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public String description() {
        return "Run a command with the project's Node.js on PATH";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value("<spec>", "Run under this Node.js instead of the project's", "--node"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("command", Arity.ONE_OR_MORE, "The command and its arguments, after --"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        Path dir = GlobalOptions.from(in).workingDir();
        Optional<NodeHome> home = NodeCli.home(dir, in.value("node").orElse(null));
        if (home.isEmpty()) {
            CommandWedge.printFail(NodeCli.WEDGE, "no Node.js declared here — pass --node <spec> or run `jk node pin`");
            return Exit.CONFIG;
        }
        return NodeCli.exec(home.get(), in.positionals(), dir);
    }
}
