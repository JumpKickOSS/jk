// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dispatch owns the envelope: one blank each side of a human command, none around machine stdout. */
class CommandDispatchEnvelopeTest {

    @Test
    void human_command_is_wrapped_in_one_blank_on_each_side() {
        String out = Capture.stdout(() -> Jk.execute("deactivate"));
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).endsWith("\n\n");
        assertThat(out).doesNotEndWith("\n\n\n");
    }

    @Test
    void script_mode_command_prints_the_payload_alone() {
        String out = Capture.stdout(() -> Jk.execute("cache", "dir"));
        assertThat(out).doesNotStartWith("\n");
        assertThat(out).doesNotEndWith("\n\n");
        assertThat(out.lines()).hasSize(1);
    }

    @Test
    void an_escaping_exception_is_reported_inside_the_envelope() {
        CliCommand boom = new CliCommand() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "always throws";
            }

            @Override
            public int run(Invocation in) {
                throw new IllegalStateException("engine went away");
            }
        };
        // No shipped command can be made to throw on demand without an engine or a project, and the
        // error line is the one piece of chrome dispatch prints itself.
        Capture.Streams streams =
                Capture.both(() -> assertThat(CommandDispatch.dispatch(boom, "jk boom", List.of(), false))
                        .isEqualTo(1));
        assertThat(streams.err()).isEqualTo("\nerror: engine went away\n\n");
        assertThat(streams.out()).isEmpty();
    }

    @Test
    void an_escaping_exception_leaves_its_stack_in_the_client_log(@TempDir Path state) throws IOException {
        CliCommand missing = new CliCommand() {
            @Override
            public String name() {
                return "missing";
            }

            @Override
            public String description() {
                return "fails on a file system error that names no file";
            }

            @Override
            public int run(Invocation in) throws IOException {
                throw new IOException("No such file or directory");
            }
        };
        String previous = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        try {
            String err = Capture.stderr(() -> CommandDispatch.dispatch(missing, "jk missing", List.of(), false));
            assertThat(err).isEqualTo("\nerror: No such file or directory\n\n");
            assertThat(Files.readString(state.resolve(CliFailure.LOG_NAME)))
                    .contains("java.io.IOException: No such file or directory")
                    .contains("at cc.jumpkick.cli.CommandDispatchEnvelopeTest");

            String verbose =
                    Capture.stderr(() -> CommandDispatch.dispatch(missing, "jk missing", List.of("-v"), false));
            assertThat(verbose).contains("at cc.jumpkick.cli.CommandDispatchEnvelopeTest");
        } finally {
            if (previous == null) System.clearProperty("jk.env.JK_STATE_DIR");
            else System.setProperty("jk.env.JK_STATE_DIR", previous);
        }
    }

    @Test
    void parse_errors_print_outside_the_envelope() {
        // `hook-env` requires -s: the usage error is reported before dispatch begins a command,
        // so there is no envelope for it to open and nothing to close it. 64 is Exit.USAGE, spelled
        // as the literal a shell would see — an assertion on the constant would follow a value
        // change and never notice it (moved this path off 2, jk's bad-config code).
        String err = Capture.stderr(() -> assertThat(Jk.execute("hook-env")).isEqualTo(64));
        assertThat(err).doesNotStartWith("\n");
        assertThat(err).contains("missing required argument");
    }
}
