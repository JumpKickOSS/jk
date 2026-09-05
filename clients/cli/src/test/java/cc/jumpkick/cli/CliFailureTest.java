// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The last catch: what the user reads, what the log keeps, and the exit code. */
class CliFailureTest {

    @TempDir
    Path state;

    private String previousState;

    @BeforeEach
    void isolateState() {
        previousState = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
    }

    @AfterEach
    void restoreState() {
        if (previousState == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", previousState);
    }

    @Test
    void an_unhandled_exception_is_one_error_wedge_a_log_entry_and_exit_70() throws IOException {
        RuntimeException boom = new IllegalStateException("the widget broke");
        int[] code = new int[1];
        String err = Capture.stderr(() -> code[0] = CliFailure.unhandled(boom, new String[] {"build", "--no-ansi"}));

        assertThat(code[0]).isEqualTo(Exit.SOFTWARE);
        assertThat(err).contains("Error").contains("the widget broke");
        assertThat(err).as("the stack stays in the log unless asked for").doesNotContain("IllegalStateException");
        String log = Files.readString(state.resolve(CliFailure.LOG_NAME));
        assertThat(log).contains("jk build --no-ansi").contains("java.lang.IllegalStateException: the widget broke");
        assertThat(log).contains("at cc.jumpkick.cli.CliFailureTest");
    }

    @Test
    void an_error_is_handled_the_same_way_and_a_blank_message_falls_back_to_the_class() throws IOException {
        Error error = new NoClassDefFoundError();
        int[] code = new int[1];
        String err = Capture.stderr(() -> code[0] = CliFailure.unhandled(error, new String[] {"tree"}));
        assertThat(code[0]).isEqualTo(Exit.SOFTWARE);
        assertThat(err).contains("NoClassDefFoundError");
        assertThat(Files.readString(state.resolve(CliFailure.LOG_NAME))).contains("java.lang.NoClassDefFoundError");
    }

    @Test
    void verbose_adds_the_stack_to_the_human_stream() {
        String err = Capture.stderr(
                () -> CliFailure.unhandled(new IllegalStateException("noisy"), new String[] {"-v", "build"}));
        assertThat(err)
                .contains("noisy")
                .contains("java.lang.IllegalStateException")
                .contains("at cc.jumpkick.cli.CliFailureTest");
    }

    @Test
    void json_output_gets_one_machine_line_and_no_wedge() {
        String[] out = new String[1];
        String err = Capture.stderr(() -> out[0] = Capture.stdout(() -> CliFailure.unhandled(
                new IllegalStateException("machine"), new String[] {"build", "--output", "json"})));
        assertThat(err).isEmpty();
        String line = out[0].strip();
        assertThat(line.lines()).hasSize(1);
        assertThat(Jsonl.str(line, "type")).isEqualTo("error");
        assertThat(Jsonl.str(line, "message")).isEqualTo("machine");
        assertThat(Jsonl.str(line, "exceptionClass")).isEqualTo("java.lang.IllegalStateException");
        assertThat(Jsonl.intValue(line, "exit", -1)).isEqualTo(Exit.SOFTWARE);
    }

    @Test
    void a_vanished_working_directory_is_a_warning_not_the_error_path() {
        Error graal = new Error("Properties init: Could not determine current working directory.");
        assertThat(CliFailure.isWorkingDirectoryGone(graal)).isTrue();
        assertThat(CliFailure.isWorkingDirectoryGone(new Error("something else")))
                .isFalse();

        int[] code = new int[1];
        String err = Capture.stderr(
                () -> code[0] = CliFailure.workingDirectoryGone(new String[] {"--version", "--no-ansi"}));
        assertThat(code[0]).isNotZero().isNotEqualTo(Exit.SOFTWARE);
        assertThat(err).contains(CliFailure.CWD_GONE).doesNotContain("Error").doesNotContain("sun.nio.fs");
        assertThat(state.resolve(CliFailure.LOG_NAME))
                .as("not a defect, so not journaled")
                .doesNotExist();
    }

    @Test
    void a_missing_or_unwritable_state_root_never_masks_the_failure() {
        System.setProperty("jk.env.JK_STATE_DIR", state.resolve("a-file").toString());
        try {
            Files.writeString(state.resolve("a-file"), "not a directory");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        int code = CliFailure.unhandled(new IllegalStateException("still reported"), new String[] {"build"});
        assertThat(code).isEqualTo(Exit.SOFTWARE);
    }
}
