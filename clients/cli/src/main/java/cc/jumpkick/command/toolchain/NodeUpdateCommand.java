// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk node update} — refresh the catalog and report the project's Node.js against the newest
 * release of its major. {@code jk update} moves the lock; {@code jk update --major} the major.
 */
public final class NodeUpdateCommand implements CliCommand {

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Refresh the Node.js catalog and check the project's pin";
    }

    @Override
    public int run(Invocation in) throws Exception {
        List<NodeRelease> releases;
        try {
            releases = NodeCli.catalog().releases(true);
        } catch (IOException e) {
            CommandWedge.printFail(NodeCli.WEDGE, "Node.js catalog unavailable: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        Optional<NodeInstalls.ProjectNode> project =
                NodeInstalls.project(GlobalOptions.from(in).workingDir());
        boolean json = GlobalOptions.outputIsJson(in);
        if (project.isEmpty()) {
            if (json) NodeCli.row(EngineProtocol.NODE_UPDATE, f -> f.number("releases", releases.size()));
            else CommandWedge.printOk(NodeCli.WEDGE, "Catalog refreshed: " + releases.size() + " releases.");
            return 0;
        }
        NodeInstalls.ProjectNode p = project.get();
        String current = p.wanted();
        int major = NodeSpec.parse(current).major();
        Optional<NodeRelease> newest = releases.stream()
                .filter(r -> !r.preRelease() && r.major() == major)
                .findFirst();
        boolean behind = newest.isPresent()
                && p.locked() != null
                && NodeDiscovery.VersionKey.of(newest.get().version())
                                .compareTo(NodeDiscovery.VersionKey.of(p.locked()))
                        > 0;
        if (json) {
            NodeCli.row(EngineProtocol.NODE_UPDATE, f -> f.number("releases", releases.size())
                    .string("current", current)
                    .optionalString("newest", newest.map(NodeRelease::version).orElse(null))
                    .bool("behind", behind));
            return 0;
        }
        if (behind) {
            CommandWedge.printOk(
                    NodeCli.WEDGE,
                    "Node.js " + current + " is locked; " + newest.get().version() + " is the newest " + major
                            + ".x — `jk update` moves the lock to it.");
        } else {
            CommandWedge.printOk(
                    NodeCli.WEDGE,
                    "Node.js " + current + " is current"
                            + newest.map(r -> " (newest " + major + ".x: " + r.version() + ")")
                                    .orElse("") + ".");
        }
        return 0;
    }
}
