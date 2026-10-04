// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import java.util.List;

/** {@code jk node} (also {@code jk nvm}) — the Node.js versions a project and this machine use. */
public final class NodeCommand extends GroupCommand {

    @Override
    public String name() {
        return "node";
    }

    @Override
    public String description() {
        return "Manage Node.js versions and installations";
    }

    @Override
    public List<String> aliases() {
        return List.of("nvm");
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new NodeListCommand(),
                new NodeListRemoteCommand(),
                new NodeInstallCommand(),
                new NodeUninstallCommand(),
                new NodeWhichCommand(),
                new NodeExecCommand(),
                new NodeRunCommand(),
                new NodePinCommand(),
                new NodeVerifyCommand(),
                new NodeUpdateCommand());
    }
}
