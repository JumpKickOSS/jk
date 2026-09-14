// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.command.Exit;
import org.junit.jupiter.api.Test;

class CaptureTest {

    @Test
    void a_non_zero_exit_echoes_the_captured_console_into_the_report_in_the_order_it_was_written() {
        String report = Capture.stdout(() -> {
            int exit = Capture.exitEchoingFailure(() -> {
                System.out.println("first, on stdout");
                System.err.println("NOT removed — these targets need a running engine:");
                return Exit.SOFTWARE;
            });
            assertThat(exit).isEqualTo(Exit.SOFTWARE);
        });
        assertThat(report)
                .startsWith(Capture.ECHO_HEADER + Exit.SOFTWARE + "\n")
                .contains("first, on stdout\nNOT removed — these targets need a running engine:\n");
    }

    @Test
    void a_zero_exit_keeps_the_captured_console_out_of_the_report() {
        String report = Capture.stdout(() -> {
            int exit = Capture.exitEchoingFailure(() -> {
                System.err.println("a warning nobody needs to read on a green run");
                return Exit.SUCCESS;
            });
            assertThat(exit).isZero();
        });
        assertThat(report).isEmpty();
    }

    @Test
    void a_body_that_throws_still_echoes_what_it_printed_and_restores_the_streams() {
        var out = System.out;
        var err = System.err;
        String report = Capture.stdout(() -> assertThatThrownBy(() -> Capture.exitEchoingFailure(() -> {
                    System.err.println("half a table");
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class));
        assertThat(report).contains("half a table");
        assertThat(System.out).isSameAs(out);
        assertThat(System.err).isSameAs(err);
    }
}
