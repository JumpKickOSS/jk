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
import org.jspecify.annotations.Nullable;

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
        return List.of(Opt.value("<spec>", "Run under this Node.js instead of the project's", "-n", "--node"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("command", Arity.ONE_OR_MORE, "The command and its arguments, after --"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        return run(launch(in));
    }

    /** A command to run: where, under which Node.js ({@code null}: the project's), and its argv. */
    public record Launch(Path dir, @Nullable String node, List<String> argv) {}

    /** The launch this invocation is. */
    public static Launch launch(Invocation in) {
        return new Launch(GlobalOptions.from(in).workingDir(), in.value("node").orElse(null), in.positionals());
    }

    /** Run {@code launch} through {@link NodeCli#passthrough}, the path every Node passthrough takes. */
    static int run(Launch launch) throws Exception {
        Optional<NodeHome> home = NodeCli.home(launch.dir(), launch.node());
        if (home.isEmpty()) {
            CommandWedge.printFail(NodeCli.WEDGE, "no Node.js declared here — pass --node <spec> or run `jk node pin`");
            return Exit.CONFIG;
        }
        return NodeCli.passthrough(home.get(), launch.argv(), launch.dir());
    }
}
