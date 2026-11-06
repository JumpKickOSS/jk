// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A plugin command's answer as the CLI prints it: whatever the command wrote comes first, its
 * failure after, and the exit is the plugin's own code rather than a flat 1.
 */
class CommandDispatchPluginReportTest {

    @Test
    void a_failed_command_prints_its_output_then_the_failure_and_exits_with_the_plugins_code() {
        var report = new PluginCommandReport("greeting table is empty", true, 3, List.of("hello, one", "hello, two"));
        int[] exit = new int[1];

        String console = interleaved(() -> exit[0] = CommandDispatch.printPluginReport("hello", List.of(), report));

        assertThat(exit[0]).isEqualTo(3);
        int first = console.indexOf("hello, one");
        int second = console.indexOf("hello, two");
        int failure = console.indexOf("greeting table is empty");
        assertThat(first).as(console).isNotNegative();
        assertThat(second).as(console).isGreaterThan(first);
        assertThat(failure).as(console).isGreaterThan(second);
    }

    @Test
    void the_output_goes_to_stdout_and_the_failure_to_stderr() {
        var report = new PluginCommandReport("greeting table is empty", true, 3, List.of("hello, one"));

        Capture.Streams streams =
                Capture.both((Runnable) () -> CommandDispatch.printPluginReport("hello", List.of(), report));

        assertThat(streams.out()).contains("hello, one").doesNotContain("greeting table is empty");
        assertThat(streams.err()).contains("greeting table is empty").doesNotContain("hello, one");
    }

    @Test
    void a_failure_reported_with_exit_zero_still_exits_non_zero() {
        var report = new PluginCommandReport("worker went away", true, 0, List.of());
        int[] exit = new int[1];

        String err = Capture.stderr(() -> exit[0] = CommandDispatch.printPluginReport("hello", List.of(), report));

        assertThat(exit[0]).isEqualTo(1);
        assertThat(err).contains("worker went away");
    }

    @Test
    void a_successful_command_prints_its_output_and_exits_with_the_plugins_code() {
        var report = new PluginCommandReport(null, true, 0, List.of("hi, consumer"));
        int[] exit = new int[] {-1};

        Capture.Streams streams =
                Capture.both((Runnable) () -> exit[0] = CommandDispatch.printPluginReport("hello", List.of(), report));

        assertThat(exit[0]).isZero();
        assertThat(streams.out()).contains("hi, consumer");
        assertThat(streams.err()).isEmpty();
    }

    /** Both console streams into one buffer, in the order {@code body} wrote them. */
    private static String interleaved(Runnable body) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream shared = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(shared);
        System.setErr(shared);
        try {
            body.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return Capture.lf(buffer.toString(StandardCharsets.UTF_8));
    }
}
