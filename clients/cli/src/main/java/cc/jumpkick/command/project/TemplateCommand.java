// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import java.util.List;

/** {@code jk template} — work on project templates (the ones {@code jk new -t} applies). */
public final class TemplateCommand extends GroupCommand {

    @Override
    public String name() {
        return "template";
    }

    @Override
    public String description() {
        return "Check project templates for jk new";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new TemplateLintCommand());
    }
}
