// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;

/**
 * {@code jk deactivate} — explain how session env and installer blocks work. Session {@code
 * JAVA_HOME}/{@code PATH} from hook-env clear when you open a new shell; permanent integration is
 * the marker block in the shell rc.
 */
public final class DeactivateCommand implements CliCommand {

    @Override
    public String name() {
        return "deactivate";
    }

    @Override
    public String description() {
        return "How to clear shell env / remove installer block";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value("<shell>", "Ignored (kept for older scripts).", "-s", "--shell"));
    }

    @Override
    public int run(Invocation in) {
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        CommandWedge.envelopeStart();
        CliOutput.out(
                JkWedge.chipLine(Glyphs.BANG, "Deactivate", nerdFont, "jk shell integration is not a session wrapper"));
        CliOutput.out("  • Directory JAVA_HOME / PATH from hook-env apply only in this shell.");
        CliOutput.out("  • Open a new terminal (or `exec $SHELL`) to drop session env.");
        CliOutput.out("  • To stop auto-hooks permanently, remove the block between");
        CliOutput.out("    `# >>> jk installer >>>` and `# <<< jk installer <<<` from each shell rc.");
        return Exit.SUCCESS;
    }
}
