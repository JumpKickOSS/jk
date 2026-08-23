// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import org.junit.jupiter.api.Test;

/**
 * Pins the verb- and flag-conditional half of the script-mode allowlist. Dispatch reads the
 * predicate before {@code run}, so it has to name the branch {@code run} then takes; each of these
 * commands keeps the two on one shared resolver, and these are the answers that resolver owes.
 * Commands that are script mode for every invocation are covered by
 * {@code ScriptModeAllowlistTest}.
 */
class ScriptModeVerbsTest {

    private static boolean script(CliCommand cmd, String... positionals) {
        Invocation.Builder in = Invocation.builder();
        for (String p : positionals) in.addPositional(p);
        return cmd.scriptMode(in.build());
    }

    @Test
    void activate_prints_a_script_only_with_a_shell_positional() {
        assertThat(script(new ActivateCommand())).isFalse();
        assertThat(script(new ActivateCommand(), "bash")).isTrue();
    }

    @Test
    void explain_graph_to_stdout_is_script_mode_but_graph_out_is_not() {
        CliCommand explain = new ExplainCommand();
        assertThat(explain.scriptMode(Invocation.builder().build())).isFalse();
        assertThat(explain.scriptMode(
                        Invocation.builder().putValue("graph", "dot").build()))
                .isTrue();
        assertThat(explain.scriptMode(Invocation.builder()
                        .putValue("graph", "dot")
                        .putValue("graph-out", "g.dot")
                        .build()))
                .isFalse();
    }

    @Test
    void ide_and_vscode_are_script_mode_only_under_print_model() {
        assertThat(script(new IdeCommand())).isFalse();
        assertThat(script(new VscodeCommand())).isFalse();
        assertThat(script(new ExportIdeaCommand())).isFalse();
        Invocation printModel = Invocation.builder().flag("print-model", true).build();
        assertThat(new IdeCommand().scriptMode(printModel)).isTrue();
        assertThat(new VscodeCommand().scriptMode(printModel)).isTrue();
        // `jk export idea` is the other IdeCommand alias — it must answer for its delegate too.
        assertThat(new ExportIdeaCommand().scriptMode(printModel)).isTrue();
    }

    @Test
    void selective_resolve_is_script_mode_prepare_and_run_are_not() {
        assertThat(script(new SelectiveCommand(), "resolve")).isTrue();
        assertThat(script(new SelectiveCommand(), "prepare")).isFalse();
        assertThat(script(new SelectiveCommand(), "run", "build")).isFalse();
    }

    @Test
    void tasks_show_is_script_mode_including_the_bare_task_shorthand() {
        assertThat(script(new TasksCommand())).isFalse();
        assertThat(script(new TasksCommand(), "show", "package-jar")).isTrue();
        assertThat(script(new TasksCommand(), "inspect", "compile-java")).isFalse();
        // `jk tasks package-jar` is the shorthand run() resolves to show.
        assertThat(script(new TasksCommand(), "package-jar")).isTrue();
    }

    @Test
    void bsp_defaults_to_serve_and_install_is_human() {
        assertThat(script(new BspCommand())).isTrue();
        assertThat(script(new BspCommand(), "install")).isFalse();
    }
}
