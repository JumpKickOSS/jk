// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import java.util.List;

/**
 * {@code jk repo} — artifact-repository credentials (vs {@code jk auth}'s git plane), stored under
 * {@code ~/.jk/repo-credentials/}.
 */
public final class RepoCommand extends GroupCommand {

    @Override
    public String name() {
        return "repo";
    }

    @Override
    public String description() {
        return "Manage artifact repository credentials";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new RepoLoginCommand(), new RepoLogoutCommand());
    }
}
