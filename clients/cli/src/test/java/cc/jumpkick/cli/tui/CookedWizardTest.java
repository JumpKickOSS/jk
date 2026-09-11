// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.Terminals;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link Wizard#run} on a live terminal under a plain theme asks its questions as cooked lines: the
 * answers come from stdin, the transcript goes to stderr, and no escape byte is written anywhere.
 * Each case pins plain mode so it holds on an ANSI developer terminal too.
 */
class CookedWizardTest {

    private static final char ESC = '\u001b';

    /** What one cooked run produced: the answers, the stderr transcript, and the tty writer's bytes. */
    private record Run(Optional<Answers> answers, String err, String tty, InputMode modeAfter) {
        Answers get() {
            return answers.orElseThrow();
        }
    }

    @Test
    void input_takes_the_default_on_empty_and_re_asks_until_the_validator_passes() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Project name:")
                        .defaultValue("demo")
                        .build())
                .step(WizardStep.InputStep.of("group", "Project group:")
                        .placeholder("com.example")
                        .validator(
                                s -> s.contains(".") ? ValidationResult.ok() : ValidationResult.error("Needs a dot."))
                        .build())
                .build();

        var run = run(wizard, "\nbad\ncom.acme\n");

        assertThat(run.get().get("name")).isEqualTo("demo");
        assertThat(run.get().get("group")).isEqualTo("com.acme");
        assertThat(run.err())
                .contains("Project name [demo]: ")
                .contains("-> demo")
                .contains("Project group (e.g. com.example): ")
                .contains("Needs a dot.")
                .contains("-> com.acme");
        assertThat(run.err().indexOf("Needs a dot.")).isLessThan(run.err().indexOf("-> com.acme"));
    }

    @Test
    void radio_accepts_a_position_an_id_empty_for_the_default_and_custom_text() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(radio("byIndex").build())
                .step(radio("byId").build())
                .step(radio("byDefault").build())
                .step(WizardStep.RadioStep.vertical("custom", "Pick or type:")
                        .choice("java", "Java")
                        .customOption("anything")
                        .build())
                .build();

        var run = run(wizard, "2\nKotlin\n\nmy-own\n");

        assertThat(run.get().get("byIndex")).isEqualTo("kotlin");
        assertThat(run.get().get("byId")).isEqualTo("kotlin");
        assertThat(run.get().get("byDefault")).isEqualTo("java");
        assertThat(run.get().get("custom")).isEqualTo("my-own");
        assertThat(run.err())
                .contains("Language:\n  1) (*) Java  hint\n  2) ( ) Kotlin\nSelect [1]: ")
                .contains("(or type your own: anything)")
                .contains("-> Kotlin")
                .contains("-> Java")
                .contains("-> my-own");
    }

    @Test
    void radio_rejects_an_unknown_answer_and_asks_again() throws Exception {
        var wizard =
                Wizard.builder().command("Test").step(radio("lang").build()).build();

        var run = run(wizard, "9\nrust\n2\n");

        assertThat(run.get().get("lang")).isEqualTo("kotlin");
        assertThat(run.err()).contains("Pick 1-2 or a listed id.");
        assertThat(run.err().split("Select \\[1\\]: ", -1)).hasSize(4);
    }

    @Test
    void multi_select_reads_spaces_commas_all_none_and_empty_for_defaults() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(multi("spaces").build())
                .step(multi("commas").build())
                .step(multi("all").build())
                .step(multi("none").build())
                .step(multi("defaults").build())
                .build();

        var run = run(wizard, "1 3\n1,3\nall\nnone\n\n");

        assertThat(run.get().getList("spaces")).containsExactly("a", "c");
        assertThat(run.get().getList("commas")).containsExactly("a", "c");
        assertThat(run.get().getList("all")).containsExactly("a", "b", "c");
        assertThat(run.get().getList("none")).isEmpty();
        assertThat(run.get().getList("defaults")).containsExactly("b");
        assertThat(run.err())
                .contains(
                        "Libraries:\n  1) [ ] Alpha\n  2) [x] Beta\n  3) [ ] Gamma\nSelect (e.g. 1 3, all, none) [2]: ")
                .contains("-> Alpha\n-> Gamma")
                .contains("-> (none selected)")
                .contains("-> Beta");
    }

    @Test
    void multi_select_appends_custom_tokens_after_the_listed_picks() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(multi("libs").customOption("group:artifact").build())
                .build();

        var run = run(wizard, "com.acme:x 1\n");

        assertThat(run.get().getList("libs")).containsExactly("a", "com.acme:x");
        assertThat(run.err()).contains("(or type your own: group:artifact)").contains("-> Alpha\n-> com.acme:x");
    }

    @Test
    void multi_select_without_a_custom_row_rejects_an_unknown_token() throws Exception {
        var wizard =
                Wizard.builder().command("Test").step(multi("libs").build()).build();

        var run = run(wizard, "1 zzz\n3\n");

        assertThat(run.get().getList("libs")).containsExactly("c");
        assertThat(run.err()).contains("Pick 1-3 or a listed id.");
    }

    @Test
    void output_step_prints_once_and_continues_without_reading() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.OutputStep.of("note", a -> List.of("first line", "second line"))
                        .prompt("Heads up")
                        .build())
                .step(radio("lang").build())
                .build();

        var run = run(wizard, "\n");

        assertThat(run.get().get("lang")).isEqualTo("java");
        assertThat(run.err()).contains("Heads up\n  first line\n  second line\n");
        assertThat(run.err().split("first line", -1)).hasSize(2);
    }

    @Test
    void preset_answers_are_shown_settled_and_not_asked() throws Exception {
        var wizard = Wizard.builder()
                .command("New")
                .subtitle("Create a project")
                .step(WizardStep.InputStep.of("name", "Project name:").build())
                .step(radio("lang").build())
                .build();

        var run = run(wizard, "\n", Answers.of(Map.of("name", "preset-app")));

        assertThat(run.get().get("name")).isEqualTo("preset-app");
        assertThat(run.err()).contains("jk: = New > Create a project").contains("Project name:\n-> preset-app");
        assertThat(run.err()).doesNotContain("Project name [");
    }

    @Test
    void cooked_run_never_enters_prompt_mode_and_writes_no_escape_bytes() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name:").build())
                .step(radio("lang").build())
                .step(multi("libs").build())
                .build();

        var run = run(wizard, "x\n\n\n");

        assertThat(run.answers()).isPresent();
        assertThat(run.err()).doesNotContain(String.valueOf(ESC));
        assertThat(run.tty()).isEmpty();
        assertThat(run.modeAfter()).isEqualTo(InputMode.COOKED);
    }

    @Test
    void eof_yields_empty_and_ends_the_open_line() throws Exception {
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name:").build())
                .build();

        var run = run(wizard, "");

        assertThat(run.answers()).isEmpty();
        assertThat(run.err()).endsWith("Name: \n");
    }

    private static WizardStep.RadioStep.Builder radio(String key) {
        return WizardStep.RadioStep.horizontal(key, "Language:")
                .choice("java", "Java", "hint")
                .choice("kotlin", "Kotlin")
                .defaultChoice("java");
    }

    private static WizardStep.MultiSelectStep.Builder multi(String key) {
        return WizardStep.MultiSelectStep.vertical(key, "Libraries:")
                .choice("a", "Alpha")
                .choice("b", "Beta")
                .choice("c", "Gamma")
                .defaults(Set.of("b"));
    }

    private static Run run(Wizard wizard, String stdin) throws Exception {
        return run(wizard, stdin, Answers.of(Map.of()));
    }

    private static Run run(Wizard wizard, String stdin, Answers preset) throws Exception {
        InputStream savedIn = System.in;
        PrintStream savedErr = System.err;
        var err = new ByteArrayOutputStream();
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Optional<Answers> answers = NoAnsi.forced(() -> wizard.run(tty, preset));
            String transcript = err.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            String written = new String(tty.written(), StandardCharsets.UTF_8);
            return new Run(answers, transcript, written, tty.mode());
        } finally {
            System.setIn(savedIn);
            System.setErr(savedErr);
        }
    }
}
