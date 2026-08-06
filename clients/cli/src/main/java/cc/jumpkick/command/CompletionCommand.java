// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.BuildPlanWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.List;

/** {@code jk completion [shell]} — write shell completion scripts under the data directory. */
public final class CompletionCommand implements CliCommand {

    @Override
    public String name() {
        return "completion";
    }

    @Override
    public String description() {
        return "Write shell completion scripts (bash, zsh, fish, pwsh)";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "shell",
                Arity.ZERO_OR_ONE,
                "Optional: bash | zsh | fish | pwsh (default: all)."));
    }

    @Override
    public int run(Invocation in) throws Exception {
        // Always refresh the full set — cheap and keeps activate wiring consistent.
        Path root = ShellCompletions.writeAll();
        boolean nerdfont = GlobalConfig.nerdfont();
        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        CliOutput.out(BuildPlanWedge.chipLine(
                Glyphs.CHECK,
                "Completion",
                nerdfont,
                "Wrote completions under " + Theme.colorize(root.toString(), t.path())));
        return Exit.SUCCESS;
    }
}
