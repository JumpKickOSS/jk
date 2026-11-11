// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/**
 * A live region animates only on a stream that reaches a terminal. A test JVM's stdout and stderr
 * are pipes, as they are for {@code jk clean > log} or {@code jk outdated | less}.
 */
class LiveLineTerminalTest {

    @Test
    void the_process_streams_redirected_to_a_pipe_are_not_animated() {
        assertThat(LiveLine.drawsOnTerminal(System.out)).isFalse();
        assertThat(LiveLine.drawsOnTerminal(System.err)).isFalse();
    }

    @Test
    void a_caller_s_own_stream_keeps_the_theme_s_say() {
        assertThat(LiveLine.drawsOnTerminal(new PrintStream(new ByteArrayOutputStream())))
                .isTrue();
    }
}
