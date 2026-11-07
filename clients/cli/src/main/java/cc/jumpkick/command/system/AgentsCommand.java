// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.ChangeSummary;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.project.AgentWiring;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk agents}: wire the project for the coding agents that work in it (jk's {@code AGENTS.md}
 * section, MCP registration, tool permissions, the pre-tool-use hook, a first code graph), or take
 * jk's entries back out. See {@code docs/user/agents.md}.
 */
public final class AgentsCommand implements CliCommand {

    @Override
    public String name() {
        return "agents";
    }

    @Override
    public String description() {
        return "Wire this project for coding agents; --remove undoes it";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Wire every supported agent, not only those in use here.", "-A", "--all"),
                Opt.flag("Take jk's entries out instead.", "-R", "--remove"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("dir", Arity.ZERO_OR_ONE, "Project root (default: the workspace root)"));
    }

    @Override
    public int run(Invocation in) {
        List<String> pos = in.positionals();
        Path dir = pos.isEmpty() ? SkillCommand.projectRoot() : Path.of(pos.get(0));
        dir = WorkingDirectory.resolve(dir);
        boolean remove = in.isSet("remove");
        try {
            AgentSetup.Report report = remove
                    ? AgentWiring.remove(dir)
                    : in.isSet("all") ? AgentWiring.run(dir, AgentSetup.Mode.ALL) : AgentWiring.run(dir);
            AgentWiring.print(report, dir, "Agent notes");
            String name = Theme.paint(ChangeSummary.name(dir), Theme.active().brightCyan());
            boolean changed = !report.changes().isEmpty();
            CommandWedge.printOk(
                    "Agents",
                    remove
                            ? changed ? "Removed jk's agent wiring from " + name : "Nothing of jk's in " + name
                            : changed ? "Wired " + name + " for coding agents" : name + " is already wired");
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail("Agents", e.getMessage() == null ? "agent setup failed" : e.getMessage());
            return Exit.CONFIG;
        }
    }
}
