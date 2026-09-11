// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.Terminals;
import cc.jumpkick.testing.Await;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Drives the wizard against {@link MemoryTerminal}. The terminal is fed bytes through a
 * {@link PipedOutputStream}; the wizard reads via {@code readKey}. Every run pins ANSI mode: under a
 * plain theme the wizard reads whole lines from stdin instead (see {@link CookedWizardTest}).
 */
class WizardTest {

    /**
     * Wizard.run drains stdin for ~40ms on entry ({@code drainInput}). Keys written before that
     * are discarded — always {@link #waitReady} after submit before typing.
     */
    private record Harness(MemoryTerminal tty, PipedOutputStream input, AtomicInteger drawnBeforeKey) {
        String output() {
            return new String(tty.written(), StandardCharsets.UTF_8);
        }
    }

    private static Harness newHarness() throws IOException {
        var pipeIn = new PipedInputStream(8192);
        var pipeOut = new PipedOutputStream(pipeIn);
        var tty = Terminals.memory(pipeIn, new ByteArrayOutputStream());
        return new Harness(tty, pipeOut, new AtomicInteger());
    }

    /** Feed {@code bytes}, recording how much the wizard had drawn first (see {@link #settle}). */
    private static void write(Harness h, byte... bytes) throws IOException {
        h.drawnBeforeKey().set(h.tty().written().length);
        h.input().write(bytes);
        h.input().flush();
    }

    private static <T> T await(Future<T> f) throws Exception {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        }
    }

    /** Wait until the wizard has drawn {@code prompt} (post-drain). */
    private static void waitReady(Harness h, String prompt) throws Exception {
        Await.until(Duration.ofSeconds(2), () -> h.output().contains(prompt), () -> "wizard never showed: " + prompt);
    }

    /**
     * Wait until the wizard has redrawn in response to the last {@link #write} — the observable
     * effect of it having consumed those bytes. Ordering matters here: a navigation key that gets
     * merged into the next read is a different key sequence, which is why keystrokes cannot simply
     * be written back to back.
     *
     * <p>This was a bare {@code Thread.sleep(10)}. Ten milliseconds was a guess about how fast this
     * machine drains a pipe: too short under load (a flake) and, worse, unable to prove the key had
     * been consumed at all, so the negative assertions it guarded could pass having verified
     * nothing. A redraw is a fact; wait for the fact.
     */
    private static void settle(Harness h) throws Exception {
        int before = h.drawnBeforeKey().get();
        Await.until(
                Duration.ofSeconds(5),
                () -> h.tty().written().length > before,
                () -> "the wizard never redrew after the last keystroke (still " + before
                        + " bytes drawn) — the key was not consumed");
    }

    @Test
    void input_step_records_typed_string() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Project name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Project name");
            write(h, (byte) 'f', (byte) 'o', (byte) 'o', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("foo");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void radio_step_records_default_on_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.RadioStep.horizontal("lang", "Language")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice("java")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Language");
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("lang")).isEqualTo("java");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void radio_step_navigates_right_then_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.RadioStep.horizontal("lang", "Language")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice("java")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Language");
            write(h, (byte) 0x1B, (byte) '[', (byte) 'C');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("lang")).isEqualTo("kotlin");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void multi_select_toggles_with_space() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.MultiSelectStep.vertical("deps", "Dependencies")
                        .choice("lombok", "Lombok")
                        .choice("guava", "Guava")
                        .choice("commons-io", "Commons IO")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Dependencies");
            // Select first (lombok), move down twice, select third (commons-io), enter.
            write(h, (byte) 0x20);
            settle(h);
            write(h, (byte) 0x1B, (byte) '[', (byte) 'B');
            settle(h);
            write(h, (byte) 0x1B, (byte) '[', (byte) 'B');
            settle(h);
            write(h, (byte) 0x20);
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("deps")).containsExactly("lombok", "commons-io");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void multi_select_toggle_all_with_a() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.MultiSelectStep.vertical("deps", "Dependencies")
                        .choice("lombok", "Lombok")
                        .choice("guava", "Guava")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Dependencies");
            write(h, (byte) 'a');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("deps")).containsExactly("lombok", "guava");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void output_step_advances_on_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .step(WizardStep.OutputStep.of("preview", a -> List.of("Hello " + a.get("name")))
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            write(h, (byte) 'b', (byte) 'a', (byte) 'r', (byte) 0x0A);
            waitReady(h, "Hello"); // OutputStep preview line (ANSI may wrap)
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("bar");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void ctrl_c_returns_empty() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            write(h, (byte) 0x03);
            var answers = await(result);
            assertThat(answers).isEmpty();
            assertThat(h.tty().mode()).isEqualTo(InputMode.COOKED);
            assertThat(h.tty().isLive()).isTrue();
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void conditional_step_is_skipped_when_predicate_false() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.RadioStep.horizontal("mode", "Mode")
                        .choice("lib", "Library")
                        .choice("bin", "Binary")
                        .defaultChoice("lib")
                        .build())
                .step(WizardStep.InputStep.of("main", "Main class")
                        .when(a -> "bin".equals(a.get("mode")))
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Mode");
            // Accept default ("lib") and let the wizard skip the conditional step.
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("mode")).isEqualTo("lib");
            assertThat(answers.asMap()).doesNotContainKey("main");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void backspace_deletes_char() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            write(h, (byte) 'a', (byte) 'b', (byte) 'c', (byte) 0x7F, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("ab");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void right_arrow_realizes_placeholder_into_input() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name")
                        .placeholder("widget")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            // Right-arrow then Enter → placeholder becomes the answer.
            write(h, (byte) 0x1B, (byte) '[', (byte) 'C');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void right_arrow_realized_text_can_be_edited_further() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name")
                        .placeholder("widget")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            // Realize "widget", then append "-2" → final = "widget-2".
            write(h, (byte) 0x1B, (byte) '[', (byte) 'C');
            settle(h);
            write(h, (byte) '-', (byte) '2', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget-2");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void input_step_seeds_buffer_from_initial_value_fn() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .step(WizardStep.InputStep.of("artifact", "Artifact")
                        // pre-populate with the project name from the prior step
                        .initialValueFn(a -> a.get("name"))
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            // Step 1: type "widget", Enter → name = "widget".
            write(h, (byte) 'w', (byte) 'i', (byte) 'd', (byte) 'g', (byte) 'e', (byte) 't', (byte) 0x0A);
            waitReady(h, "Artifact");
            // Step 2: just Enter → artifact buffer was pre-seeded with "widget",
            // and Enter records the buffer contents (defaultValue stays empty).
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("artifact")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void tab_realizes_placeholder_into_input() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name")
                        .placeholder("widget")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            // Tab then Enter → placeholder becomes the answer.
            write(h, (byte) 0x09);
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void radio_vertical_custom_option_records_typed_text() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.RadioStep.vertical("fruit", "Pick a fruit")
                        .choice("apple", "Apple")
                        .choice("banana", "Banana")
                        .choice("grape", "Grape")
                        .customOption("Enter your own fruit")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Pick a fruit");
            // Down 3× from apple(0) → custom row (index 3), type "mango", Enter.
            for (int i = 0; i < 3; i++) {
                write(h, (byte) 0x1B, (byte) '[', (byte) 'B');
                settle(h);
            }
            write(h, (byte) 'm', (byte) 'a', (byte) 'n', (byte) 'g', (byte) 'o');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("fruit")).isEqualTo("mango");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void radio_vertical_custom_option_present_still_selects_a_choice_id() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.RadioStep.vertical("fruit", "Pick a fruit")
                        .choice("apple", "Apple")
                        .choice("banana", "Banana")
                        .choice("grape", "Grape")
                        .customOption("Enter your own fruit")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Pick a fruit");
            // Down once → banana, Enter → the choice id, not custom text.
            write(h, (byte) 0x1B, (byte) '[', (byte) 'B');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("fruit")).isEqualTo("banana");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void radio_vertical_custom_option_rejects_empty_then_accepts_text() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.RadioStep.vertical("fruit", "Pick a fruit")
                        .choice("apple", "Apple")
                        .customOption("Enter your own fruit")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Pick a fruit");
            // Move to the (empty) custom row and press Enter — must NOT advance.
            write(h, (byte) 0x1B, (byte) '[', (byte) 'B');
            settle(h);
            write(h, (byte) 0x0A);
            // One redraw is the proof the Enter was CONSUMED — so "still not done" is a fact about
            // the wizard's decision, not about how long we waited.
            settle(h);
            assertThat(result.isDone()).isFalse();
            // Now type a value and Enter — it commits the typed text.
            write(h, (byte) 'k', (byte) 'i', (byte) 'w', (byte) 'i');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("fruit")).isEqualTo("kiwi");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void multi_select_custom_option_appends_typed_text() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.MultiSelectStep.vertical("fruit", "Pick fruits")
                        .choice("apple", "Apple")
                        .choice("banana", "Banana")
                        .choice("grape", "Grape")
                        .customOption("Enter your own fruit")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Pick fruits");
            // Space → check apple(0); Down 3× → custom row; type "kiwi"; Enter.
            write(h, (byte) 0x20);
            settle(h);
            for (int i = 0; i < 3; i++) {
                write(h, (byte) 0x1B, (byte) '[', (byte) 'B');
                settle(h);
            }
            write(h, (byte) 'k', (byte) 'i', (byte) 'w', (byte) 'i');
            settle(h);
            write(h, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("fruit")).containsExactly("apple", "kiwi");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }

    @Test
    void right_arrow_is_a_noop_when_placeholder_is_empty() throws Exception {
        // No placeholder → Right is ignored (Enter falls through to default
        // value, which here is also empty, so input is just "").
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> NoAnsi.forcedAnsi(() -> wizard.run(h.tty())));
            waitReady(h, "Name");
            write(h, (byte) 0x1B, (byte) '[', (byte) 'C');
            settle(h);
            write(h, (byte) 'h', (byte) 'i', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("hi");
        } finally {
            exec.shutdownNow();
            h.tty().close();
        }
    }
}
