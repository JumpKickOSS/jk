// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.command.project.AgentWiring;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.docs.AgentsGuide;
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

    /** The result lines are the output, so stdout is byte-exact. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) {
        List<String> pos = in.positionals();
        Path dir = pos.isEmpty() ? SkillCommand.projectRoot() : Path.of(pos.get(0));
        dir = Path.of("").toAbsolutePath().resolve(dir).normalize();
        try {
            List<String> lines = in.isSet("remove")
                    ? AgentWiring.remove(dir)
                    : in.isSet("all") ? AgentWiring.run(dir, AgentSetup.Mode.ALL) : AgentWiring.run(dir);
            String none =
                    in.isSet("remove") ? "nothing of jk's in " + dir : dir.resolve(AgentsGuide.FILE) + " is current";
            CliOutput.outRaw((lines.isEmpty() ? none : String.join("\n", lines)) + "\n");
            CliOutput.stdout().flush();
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err(e.getMessage() == null ? "agent setup failed" : e.getMessage());
            return Exit.CONFIG;
        }
    }
}
