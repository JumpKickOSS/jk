// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk node uninstall <version>} — remove one of jk's own Node.js installs. Another manager's
 * install is never touched.
 */
public final class NodeUninstallCommand implements CliCommand {

    @Override
    public String name() {
        return "uninstall";
    }

    @Override
    public String description() {
        return "Remove a Node.js version jk installed";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("version", Arity.ONE, "The exact version, as `jk node list` shows it"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String raw = in.positionals().get(0).trim();
        String version = raw.startsWith("v") ? raw.substring(1) : raw;
        String bad = ToolRegistry.invalidVersion(version);
        if (bad != null) {
            CommandWedge.printFail(NodeCli.WEDGE, bad);
            return Exit.USAGE;
        }
        Optional<InstalledTool> installed = NodeCli.installs().managedTool(BuildTool.NODE, version);
        if (installed.isEmpty()) {
            boolean elsewhere =
                    NodeCli.installs().all().stream().anyMatch(i -> i.version().equals(version));
            CliOutput.out("Node.js " + version + " is not a jk install"
                    + (elsewhere ? "; another manager installed it, and jk leaves it alone." : "."));
            return 0;
        }
        PathUtil.deleteRecursively(installed.get().home());
        if (GlobalOptions.outputIsJson(in)) {
            NodeCli.row(EngineProtocol.NODE_UNINSTALLED, f -> f.string("version", version)
                    .string("home", installed.get().home().toString()));
            return 0;
        }
        CommandWedge.printOk(NodeCli.WEDGE, "Removed Node.js " + version);
        return 0;
    }
}
