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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code jk node run <script>} — run a {@code package.json} script with the project's package manager. */
public final class NodeRunCommand implements CliCommand {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "Run a package.json script with the project's package manager";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value("<spec>", "Run under this Node.js instead of the project's", "--node"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("script", Arity.ONE_OR_MORE, "The script, then its arguments"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        Path dir = GlobalOptions.from(in).workingDir();
        Optional<NodeHome> home = NodeCli.home(dir, in.value("node").orElse(null));
        if (home.isEmpty()) {
            CommandWedge.printFail(NodeCli.WEDGE, "no Node.js declared here — pass --node <spec> or run `jk node pin`");
            return Exit.CONFIG;
        }
        List<String> argv = new ArrayList<>(home.get().managerCommand());
        argv.add("run");
        argv.addAll(in.positionals());
        return NodeCli.exec(home.get(), argv, dir);
    }
}
