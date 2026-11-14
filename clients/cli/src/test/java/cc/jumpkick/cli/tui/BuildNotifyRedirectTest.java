// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.StreamCaps;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.config.JkConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@code BuildRun.notify} hands {@link BuildNotify} {@code CliOutput.stdout()} from both the live
 * and the headless (pipe) settle. A test JVM's stdout is a pipe, as {@code jk build > build.log} is.
 */
class BuildNotifyRedirectTest {

    @Test
    void a_redirected_stdout_receives_no_desktop_notification_escape() {
        GlobalOptions g = new GlobalOptions();
        g.notify = JkConfig.NotifyChoice.ALWAYS; // --notify, or any build of a minute or more under AUTO
        PrintStream real = System.out;
        var buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            CliOutput.beginCommand(false);
            BuildNotify.maybeNotify(CliOutput.stdout(), g, BuildNotify.Outcome.COMPLETE, "cc.jumpkick:jk", 0, 90_000);
            System.out.flush();
        } finally {
            System.setOut(real);
            CliOutput.beginCommand(false);
        }
        assertThat(buf.toString(StandardCharsets.UTF_8))
                .as("bytes build.log received")
                .doesNotContain("\u001b]99;");
    }

    @Test
    void with_only_stdout_redirected_the_notification_goes_to_the_terminal_on_stderr() throws Exception {
        assumeFalse(StreamCaps.forcedNonInteractive(), "CI or TERM=dumb turns every terminal OSC off");
        GlobalOptions g = new GlobalOptions();
        g.notify = JkConfig.NotifyChoice.ALWAYS;
        Capture.Streams streams = Capture.both(() -> {
            try {
                StreamCaps.asTerminal(false, true, () -> {
                    CliOutput.beginCommand(false);
                    BuildNotify.maybeNotify(
                            CliOutput.stdout(), g, BuildNotify.Outcome.COMPLETE, "cc.jumpkick:jk", 0, 90_000);
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                CliOutput.beginCommand(false);
            }
        });
        assertThat(streams.out()).as("the redirected log").doesNotContain("\u001b");
        assertThat(streams.err()).as("the terminal").contains("\u001b]99;");
    }
}
