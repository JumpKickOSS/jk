// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Ctrl-Z with stderr redirected: the stop writes no escape into the file or pipe stderr goes to. */
class TerminalsStopTest {

    @Test
    void a_stop_writes_nothing_to_a_stderr_that_is_not_a_terminal() {
        assumeFalse(Terminals.stderrIsTty(), "this JVM's stderr is a terminal");
        PrintStream before = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Terminals.suspendForStop();
        } finally {
            System.setErr(before);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
