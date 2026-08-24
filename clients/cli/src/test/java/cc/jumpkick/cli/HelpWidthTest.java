// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.terminal.Width;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lock in the 78-column help budget (5fe0aa0a). {@link HelpRenderer} does no wrapping, so
 * any description edit that pushes a rendered row past the budget silently regresses every
 * terminal at the classic 80-col width — this test renders <em>every</em> registered command
 * model's help screen (top level plus each subcommand at any depth, via the same
 * {@link CommandModels} → {@link HelpRenderer#renderHelp} path the CLI dispatch uses) and fails
 * on the first line wider than 78 visible columns.
 */
class HelpWidthTest {

    private static final int BUDGET = 78;

    /** Visible columns of one help line once ANSI chrome is stripped (wcwidth-aware). */
    private static int visibleColumns(String line) {
        String stripped = TestAnsi.strip(line);
        return Width.columns(stripped);
    }

    private static void check(String screenName, String help, List<String> violations) {
        for (String line : help.split("\\R", -1)) {
            int cols = visibleColumns(line);
            if (cols > BUDGET) {
                violations.add(screenName + ": " + cols + " cols: " + TestAnsi.strip(line));
            }
        }
    }

    /** Same model assembly as {@code CommandDispatch.renderHelp}. */
    private static String renderHelp(CliCommand cmd, String qualified) {
        List<OptionModel> globals = new ArrayList<>();
        for (var g : GlobalOptions.globalOpts()) {
            if (!g.hidden()) globals.add(CommandModels.option(g));
        }
        return HelpRenderer.renderHelp(CommandModels.from(cmd, qualified, globals), /* ansi */ false);
    }

    private static void checkTree(CliCommand cmd, String qualified, List<String> violations) {
        check(qualified + " --help", renderHelp(cmd, qualified), violations);
        for (CliCommand sub : cmd.subcommands()) {
            checkTree(sub, qualified + " " + sub.name(), violations);
        }
    }

    @Test
    void every_command_help_screen_fits_78_columns() {
        List<String> violations = new ArrayList<>();
        for (CliCommand cmd : CommandDispatch.commands()) {
            checkTree(cmd, "jk " + cmd.name(), violations);
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void top_level_help_screens_fit_78_columns() {
        List<String> violations = new ArrayList<>();
        check("jk --help", Capture.stdout(() -> assertThat(Jk.execute("--help")).isZero()), violations);
        check("jk", Capture.stdout(() -> assertThat(Jk.execute()).isZero()), violations);
        assertThat(violations).isEmpty();
    }
}
