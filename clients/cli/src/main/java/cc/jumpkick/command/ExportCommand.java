// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import java.util.List;

/**
 * {@code jk export} — translate a jk project to Gradle, Maven, IntelliJ, or a lock-scoped BOM.
 */
public final class ExportCommand extends GroupCommand {

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String description() {
        return "Export to Gradle, Maven, IntelliJ IDEA, or a BOM";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new ExportGradleCommand(), new ExportMavenCommand(), new ExportBomCommand(), new ExportIdeaCommand());
    }
}
