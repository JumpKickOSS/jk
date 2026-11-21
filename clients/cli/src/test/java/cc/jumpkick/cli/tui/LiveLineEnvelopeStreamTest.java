// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.StreamCaps;
import cc.jumpkick.terminal.Ansi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@code CliOutput.stdout()} is the stream {@code jk clean}, {@code jk outdated}, {@code jk graph},
 * the JDK/tool install views and {@code jk jdk uninstall} hand their live region. A test JVM's
 * stdout is a pipe, as it is for {@code jk clean > log}.
 */
class LiveLineEnvelopeStreamTest {

    @Test
    void the_envelope_stdout_over_a_pipe_is_not_a_terminal() {
        assertThat(LiveLine.drawsOnTerminal(CliOutput.stdout()))
                .as("CliOutput.stdout() over a redirected stdout reaches a terminal?")
                .isFalse();
    }

    @Test
    void a_region_on_the_envelope_stdout_writes_no_cursor_control_into_a_pipe() throws Exception {
        PrintStream real = System.out;
        var buf = new ByteArrayOutputStream();
        String written;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            CliOutput.beginCommand(false);
            NoAnsi.forcedAnsi(() -> {
                try (LiveLine line =
                        LiveLine.of(CliOutput.stdout(), f -> "Clean  Counting").open()) {
                    line.step();
                }
                return null;
            });
            System.out.flush();
            written = buf.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(real);
            CliOutput.beginCommand(false);
        }
        assertThat(written).as("bytes a redirected stdout received").doesNotContain(Ansi.HIDE_CURSOR);
    }

    @Test
    void the_envelope_streams_on_a_terminal_still_animate() throws Exception {
        assumeFalse(StreamCaps.forcedNonInteractive(), "CI or TERM=dumb turns every region off");
        assertThat(StreamCaps.asTerminal(true, false, () -> LiveLine.drawsOnTerminal(CliOutput.stdout())))
                .isTrue();
        assertThat(StreamCaps.asTerminal(true, false, () -> LiveLine.drawsOnTerminal(CliOutput.stderr())))
                .as("stderr is judged on its own device")
                .isFalse();
        assertThat(StreamCaps.asTerminal(false, true, () -> LiveLine.drawsOnTerminal(CliOutput.stderr())))
                .isTrue();
    }
}
