// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.docs.AgentHook;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Hidden {@code jk agent-hook}: an agent's pre-tool-use hook. Reads the tool call as JSON on stdin
 * and answers on stdout with a deny (Maven, Gradle) or a nudge toward the code graph; silent
 * otherwise. Installed by {@link cc.jumpkick.docs.AgentSetup}.
 */
public final class AgentHookCommand implements CliCommand {

    @Override
    public String name() {
        return "agent-hook";
    }

    @Override
    public String description() {
        return "Internal: an agent's pre-tool-use hook";
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    /** The reply is JSON the agent parses, so stdout is byte-exact. */
    @Override
    public boolean scriptMode(Invocation in) {
        return true;
    }

    @Override
    public int run(Invocation in) throws IOException {
        String reply = AgentHook.decide(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        if (reply != null) {
            CliOutput.outRaw(reply + "\n");
            CliOutput.stdout().flush();
        }
        return Exit.SUCCESS;
    }
}
