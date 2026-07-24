// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

/**
 * Drives the wizard against JLine's {@link DumbTerminal}. The terminal is fed bytes through a
 * {@link PipedOutputStream}; the wizard reads via its normal {@code term.reader()} path. Raw-mode
 * signal/attribute integration is exercised manually in Task 7 — DumbTerminal's no-op {@code
 * enterRawMode} is enough to verify the state machine.
 */
class WizardTest {

    /**
     * Wizard.run drains stdin for ~40ms on entry ({@code drainInput}). Keys written before that
     * are discarded — always {@link #waitReady} after submit before typing.
     */
    private static final long READY_POLL_MS = 2L;

    private record Harness(DumbTerminal terminal, PipedOutputStream input, ByteArrayOutputStream output) {}

    private static Harness newHarness() throws IOException {
        var pipeIn = new PipedInputStream(8192);
        var pipeOut = new PipedOutputStream(pipeIn);
        var sink = new ByteArrayOutputStream();
        var term = new DumbTerminal("test", "ansi", pipeIn, sink, StandardCharsets.UTF_8);
        return new Harness(term, pipeOut, sink);
    }

    private static void write(OutputStream out, byte... bytes) throws IOException {
        out.write(bytes);
        out.flush();
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (h.output().toString(StandardCharsets.UTF_8).contains(prompt)) return;
            Thread.sleep(READY_POLL_MS);
        }
        throw new TimeoutException("wizard never showed: " + prompt);
    }

    /** Brief settle between keystrokes that must be processed in order (navigation). */
    private static void tick() throws InterruptedException {
        Thread.sleep(10);
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Project name");
            write(h.input(), (byte) 'f', (byte) 'o', (byte) 'o', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("foo");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Language");
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("lang")).isEqualTo("java");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Language");
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("lang")).isEqualTo("kotlin");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Dependencies");
            // Select first (lombok), move down twice, select third (commons-io), enter.
            write(h.input(), (byte) 0x20);
            tick();
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
            tick();
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
            tick();
            write(h.input(), (byte) 0x20);
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("deps")).containsExactly("lombok", "commons-io");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Dependencies");
            write(h.input(), (byte) 'a');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("deps")).containsExactly("lombok", "guava");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void output_step_advances_on_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .step(WizardStep.OutputStep.of("preview", a -> java.util.List.of("Hello " + a.get("name")))
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            write(h.input(), (byte) 'b', (byte) 'a', (byte) 'r', (byte) 0x0A);
            waitReady(h, "Hello"); // OutputStep preview line (ANSI may wrap)
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("bar");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void ctrl_c_returns_empty() throws Exception {
        var h = newHarness();
        var savedBefore = h.terminal().getAttributes();
        var wizard = Wizard.builder()
                .command("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            write(h.input(), (byte) 0x03);
            var answers = await(result);
            assertThat(answers).isEmpty();
            // Attributes should be restored to a value matching the snapshot prior to entry.
            var savedAfter = h.terminal().getAttributes();
            assertThat(savedAfter.getControlChars()).isEqualTo(savedBefore.getControlChars());
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Mode");
            // Accept default ("lib") and let the wizard skip the conditional step.
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("mode")).isEqualTo("lib");
            assertThat(answers.asMap()).doesNotContainKey("main");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            write(h.input(), (byte) 'a', (byte) 'b', (byte) 'c', (byte) 0x7F, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("ab");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            // Right-arrow then Enter → placeholder becomes the answer.
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            // Realize "widget", then append "-2" → final = "widget-2".
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            tick();
            write(h.input(), (byte) '-', (byte) '2', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget-2");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            // Step 1: type "widget", Enter → name = "widget".
            write(h.input(), (byte) 'w', (byte) 'i', (byte) 'd', (byte) 'g', (byte) 'e', (byte) 't', (byte) 0x0A);
            waitReady(h, "Artifact");
            // Step 2: just Enter → artifact buffer was pre-seeded with "widget",
            // and Enter records the buffer contents (defaultValue stays empty).
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("artifact")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            // Tab then Enter → placeholder becomes the answer.
            write(h.input(), (byte) 0x09);
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Pick a fruit");
            // Down 3× from apple(0) → custom row (index 3), type "mango", Enter.
            for (int i = 0; i < 3; i++) {
                write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
                tick();
            }
            write(h.input(), (byte) 'm', (byte) 'a', (byte) 'n', (byte) 'g', (byte) 'o');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("fruit")).isEqualTo("mango");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Pick a fruit");
            // Down once → banana, Enter → the choice id, not custom text.
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("fruit")).isEqualTo("banana");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Pick a fruit");
            // Move to the (empty) custom row and press Enter — must NOT advance.
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
            tick();
            write(h.input(), (byte) 0x0A);
            tick();
            tick();
            assertThat(result.isDone()).isFalse();
            // Now type a value and Enter — it commits the typed text.
            write(h.input(), (byte) 'k', (byte) 'i', (byte) 'w', (byte) 'i');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("fruit")).isEqualTo("kiwi");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Pick fruits");
            // Space → check apple(0); Down 3× → custom row; type "kiwi"; Enter.
            write(h.input(), (byte) 0x20);
            tick();
            for (int i = 0; i < 3; i++) {
                write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
                tick();
            }
            write(h.input(), (byte) 'k', (byte) 'i', (byte) 'w', (byte) 'i');
            tick();
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("fruit")).containsExactly("apple", "kiwi");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
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
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            waitReady(h, "Name");
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            tick();
            write(h.input(), (byte) 'h', (byte) 'i', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("hi");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }
}
