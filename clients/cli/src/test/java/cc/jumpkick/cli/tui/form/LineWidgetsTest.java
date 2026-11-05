// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import static cc.jumpkick.cli.tui.form.Scripted.BACKSPACE;
import static cc.jumpkick.cli.tui.form.Scripted.CTRL_C;
import static cc.jumpkick.cli.tui.form.Scripted.ENTER;
import static cc.jumpkick.cli.tui.form.Scripted.LEFT;
import static cc.jumpkick.cli.tui.form.Scripted.RIGHT;
import static cc.jumpkick.cli.tui.form.Scripted.TAB;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.form.Scripted.Mode;
import cc.jumpkick.cli.tui.form.Scripted.Run;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The line prompt, the path prompt and the confirm, scripted in each mode. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LineWidgetsTest {

    private static LinePrompt name() {
        return new LinePrompt("Name", "Also the directory", null, "[a-z][a-z0-9-]*", true);
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void line_prompt_shows_a_pattern_miss_and_takes_the_corrected_line(Mode mode) throws Exception {
        Run<String> run = Scripted.run(mode, name(), "Demo" + ENTER + BACKSPACE.repeat(4) + "demo" + ENTER, "");

        assertThat(run.answer()).contains("demo");
        assertThat(run.frameWith("does not match"))
                .contains("■  Name  Also the directory")
                .contains("   Demo▏")
                .contains("   'Demo' does not match [a-z][a-z0-9-]*.");
        assertThat(run.last()).containsExactly("□  Name ➜ demo");
    }

    @Test
    void line_prompt_in_plain_mode_re_asks_until_the_pattern_matches() throws Exception {
        Run<String> run = Scripted.run(Mode.PLAIN, name(), "", "Demo\n\ndemo\n");

        assertThat(run.answer()).contains("demo");
        assertThat(run.written())
                .isEqualTo("Name (Also the directory): 'Demo' does not match [a-z][a-z0-9-]*.\n"
                        + "Name (Also the directory): A value is needed.\n"
                        + "Name (Also the directory): -> demo\n");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void line_prompt_takes_its_default_on_an_empty_line(Mode mode) throws Exception {
        var prompt = new LinePrompt("Group", null, "com.example", null, false);

        Run<String> run = Scripted.run(mode, prompt, ENTER, "\n");

        assertThat(run.answer()).contains("com.example");
        if (mode == Mode.PLAIN) assertThat(run.written()).startsWith("Group [com.example]: -> com.example");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void tab_types_the_default_in_for_editing(Mode mode) throws Exception {
        var prompt = new LinePrompt("Group", null, "com.example", null, false);

        Run<String> run = Scripted.run(mode, prompt, TAB + ".app" + ENTER, "");

        assertThat(run.answer()).contains("com.example.app");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void backing_out_answers_nothing(Mode mode) throws Exception {
        Run<String> run = Scripted.run(mode, name(), "de" + CTRL_C, "");

        assertThat(run.answer()).isEmpty();
        if (mode != Mode.PLAIN) assertThat(run.last()).containsExactly("✘  Name");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void path_prompt_completes_the_common_prefix_and_lists_the_candidates(Mode mode, @TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("alpha"));
        Files.createDirectories(dir.resolve("alpine"));
        Files.createDirectories(dir.resolve("beta"));
        Files.createDirectories(dir.resolve(".hidden"));
        Files.writeString(dir.resolve("alps.txt"), "a file is not a candidate");
        var prompt = new PathPrompt("Directory", null, ".", true, PathPrompt.directories(dir));

        Run<String> run = Scripted.run(mode, prompt, "a" + TAB + "h" + TAB + ENTER, "");

        assertThat(run.answer()).contains("alpha/");
        assertThat(run.frameWith("alpine/")).contains("   alp▏").contains("   alpha/  alpine/");
        assertThat(run.last()).containsExactly("□  Directory ➜ alpha/");
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void path_prompt_says_when_nothing_completes(Mode mode, @TempDir Path dir) throws Exception {
        var prompt = new PathPrompt("Directory", null, null, true, PathPrompt.directories(dir));

        Run<String> run = Scripted.run(mode, prompt, "zz" + TAB + BACKSPACE.repeat(2) + "out" + ENTER, "");

        assertThat(run.answer()).contains("out");
        assertThat(run.frameWith("No directory")).contains("No directory starts with 'zz'.");
    }

    @Test
    void path_prompt_in_plain_mode_reads_the_line_and_takes_the_default_on_empty(@TempDir Path dir) throws Exception {
        var prompt = new PathPrompt("Directory", null, ".", true, PathPrompt.directories(dir));

        assertThat(Scripted.run(Mode.PLAIN, prompt, "", "work/apps\n").answer()).contains("work/apps");
        Run<String> run = Scripted.run(Mode.PLAIN, prompt, "", "\n");
        assertThat(run.answer()).contains(".");
        assertThat(run.written()).isEqualTo("Directory [.]: -> .\n");
    }

    @Test
    void home_relative_paths_complete_under_the_home_directory(@TempDir Path dir) {
        var completer = PathPrompt.directories(dir);

        assertThat(completer.complete("~/")).allMatch(c -> c.startsWith("~/") && c.endsWith("/"));
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void confirm_answers_on_y_or_n_and_on_enter_takes_the_focused_one(Mode mode) throws Exception {
        assertThat(Scripted.run(mode, new YesNo("Proceed", null, false), "y", "")
                        .answer())
                .contains(true);
        assertThat(Scripted.run(mode, new YesNo("Proceed", null, true), "N", "").answer())
                .contains(false);
        assertThat(Scripted.run(mode, new YesNo("Proceed", null, false), ENTER, "")
                        .answer())
                .contains(false);

        Run<Boolean> moved = Scripted.run(mode, new YesNo("Proceed", null, false), RIGHT + LEFT + RIGHT + ENTER, "");
        assertThat(moved.answer()).contains(true);
        assertThat(moved.last()).containsExactly("□  Proceed ➜ Yes");
    }

    @Test
    void confirm_marks_the_focused_answer_as_a_pill_only_in_nerd_mode() throws Exception {
        Run<Boolean> nerd = Scripted.run(Mode.NERD, new YesNo("Proceed", null, true), ENTER, "");
        Run<Boolean> ansi = Scripted.run(Mode.ANSI, new YesNo("Proceed", null, true), ENTER, "");

        assertThat(nerd.written()).contains(Scripted.PILL_CAP).contains("Yes");
        assertThat(ansi.written()).doesNotContain(Scripted.PILL_CAP).contains(" Yes ");
        assertThat(ansi.frameWith("Yes")).contains("    Yes   No");
    }

    @Test
    void confirm_in_plain_mode_shows_the_default_in_its_cue_and_re_asks_on_nonsense() throws Exception {
        Run<Boolean> run = Scripted.run(Mode.PLAIN, new YesNo("Proceed", null, false), "", "maybe\nyes\n");

        assertThat(run.answer()).contains(true);
        assertThat(run.written()).isEqualTo("Proceed [y/N]: Answer y or n.\nProceed [y/N]: -> Yes\n");
        assertThat(Scripted.run(Mode.PLAIN, new YesNo("Proceed", null, true), "", "\n")
                        .answer())
                .isEqualTo(Optional.of(true));
        assertThat(Scripted.run(Mode.PLAIN, new YesNo("Proceed", null, null), "", "\nn\n")
                        .written())
                .isEqualTo("Proceed [y/n]: Answer y or n.\nProceed [y/n]: -> No\n");
    }
}
