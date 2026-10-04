// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.runtime.HostedEvents;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code jk node install <spec>} — install a Node.js into jk's store, the same install {@code jk tool
 * install node:<spec>} and a build's {@code ensure-node} make.
 */
public final class NodeInstallCommand implements CliCommand {

    @Override
    public String name() {
        return "install";
    }

    @Override
    public String description() {
        return "Install a Node.js version";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Download even when another manager already has it", "--no-discover"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("spec", Arity.ZERO_OR_ONE, "24, 24.21, 24.21.0, lts, lts/krypton or latest (default: lts)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        String spec = in.positionals().isEmpty() ? "lts" : in.positionals().get(0);
        HostedEvents.Provision p = EngineClient.provisionTool(
                EnginePaths.current(), BuildTool.NODE.slug(), spec, JkDirs.tools(), in.isSet("no-discover"), false);
        if (p.error() != null) {
            CommandWedge.printFail(NodeCli.WEDGE, p.error());
            return p.exit() == Exit.SUCCESS ? Exit.SOFTWARE : p.exit();
        }
        if (p.exit() != Exit.SUCCESS) return p.exit();
        String source = Objects.requireNonNullElse(p.source(), "");
        String version = Objects.requireNonNullElse(p.version(), spec);
        String home = Objects.requireNonNullElse(p.bin(), "");
        if (GlobalOptions.outputIsJson(in)) {
            NodeCli.row(EngineProtocol.NODE_INSTALLED, f -> f.string("version", version)
                    .string("home", home)
                    .string("source", source.toLowerCase(Locale.ROOT))
                    .optionalNonBlankString("verification", p.verification()));
            return 0;
        }
        CommandWedge.printOk(
                NodeCli.WEDGE,
                "CACHED".equals(source)
                        ? "Node.js " + version + " is already installed at " + home
                        : "Node.js " + version + " has been installed to " + home);
        if (p.verification() != null && !p.verification().isBlank()) CliOutput.out(p.verification());
        return 0;
    }
}
