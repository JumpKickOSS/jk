// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk node list-remote} — Node.js releases from the catalog. By default the newest of each
 * major; {@code --major N} or {@code --all} lists every release they select.
 */
public final class NodeListRemoteCommand implements CliCommand {

    @Override
    public String name() {
        return "list-remote";
    }

    @Override
    public List<String> aliases() {
        return List.of("ls-remote");
    }

    @Override
    public String description() {
        return "List Node.js releases available to install";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Only LTS releases", "--lts"),
                Opt.value("<N>", "Every release of this major", "--major"),
                Opt.flag("Every release, not only the newest of each major", "--all"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        boolean lts = in.isSet("lts");
        boolean all = in.isSet("all");
        Integer major = null;
        if (in.value("major").isPresent()) {
            try {
                major = Integer.parseInt(in.value("major").get().trim());
            } catch (NumberFormatException e) {
                CommandWedge.printFail(
                        NodeCli.WEDGE,
                        "--major takes a number, got `" + in.value("major").get() + "`");
                return Exit.USAGE;
            }
        }
        List<NodeRelease> releases;
        try {
            releases = NodeCli.catalog().releases();
        } catch (IOException e) {
            CommandWedge.printFail(NodeCli.WEDGE, "Node.js catalog unavailable: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        List<NodeRelease> shown = select(releases, lts, major, all);
        if (GlobalOptions.outputIsJson(in)) {
            for (NodeRelease r : shown) {
                NodeCli.row(EngineProtocol.NODE_REMOTE, f -> f.string("version", r.version())
                        .optionalString("lts", r.lts())
                        .optionalString("npm", r.npm())
                        .bool("security", r.security()));
            }
            return 0;
        }
        if (shown.isEmpty()) {
            CommandWedge.printOk(NodeCli.WEDGE, "No Node.js release matches.");
            return 0;
        }
        List<List<String>> rows = new ArrayList<>();
        for (NodeRelease r : shown) {
            rows.add(List.of(
                    r.version(),
                    Objects.requireNonNullElse(r.lts(), ""),
                    Objects.requireNonNullElse(r.npm(), ""),
                    r.security() ? "security" : ""));
        }
        CommandWedge.envelopeStart();
        for (String line : Table.render("Node.js releases", List.of("Version", "LTS", "npm", ""), rows)) {
            CliOutput.out(line);
        }
        return 0;
    }

    /** The rows to show: pre-releases never; newest per major unless a major or {@code all} is asked. */
    static List<NodeRelease> select(List<NodeRelease> releases, boolean lts, @Nullable Integer major, boolean all) {
        List<NodeRelease> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (NodeRelease r : releases) {
            if (r.preRelease()) continue;
            if (lts && r.lts() == null) continue;
            if (major != null && r.major() != major) continue;
            if (major == null && !all && !seen.add(r.major())) continue;
            out.add(r);
        }
        return out;
    }
}
