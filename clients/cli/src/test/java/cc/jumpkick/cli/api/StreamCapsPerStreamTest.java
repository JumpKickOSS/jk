// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.JkConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Each stream is judged on its own device, in all four combinations of a terminal or a redirect on
 * stdout and stderr: a result line on stdout carries escapes exactly when stdout is a terminal, a
 * failure line on stderr exactly when stderr is, and a live region draws only on a terminal stdout.
 */
class StreamCapsPerStreamTest {

    private record Seen(String out, String err, boolean liveOnStdout, boolean liveOnStderr) {}

    private static Seen run(boolean outTty, boolean errTty) throws Exception {
        boolean[] live = new boolean[2];
        Capture.Streams streams = Capture.both(() -> {
            try {
                StreamCaps.asTerminal(
                        outTty,
                        errTty,
                        () -> NoAnsi.withConfig(JkConfig.empty(), () -> {
                            CliOutput.beginCommand(false);
                            CommandWedge.printOk("Build", "done");
                            CommandWedge.printFail("Build", "broke");
                            live[0] = StreamCaps.live(StreamCaps.Side.OUT);
                            live[1] = StreamCaps.live(StreamCaps.Side.ERR);
                            return null;
                        }));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                CliOutput.beginCommand(false);
            }
        });
        return new Seen(streams.out(), streams.err(), live[0], live[1]);
    }

    @ParameterizedTest(name = "stdout tty={0}, stderr tty={1}")
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void each_stream_follows_its_own_device(boolean outTty, boolean errTty) throws Exception {
        assumeFalse(StreamCaps.forcedNonInteractive(), "TERM=dumb or CI turns ANSI off everywhere");
        Seen seen = run(outTty, errTty);
        assertThat(seen.out()).contains("done");
        assertThat(seen.err()).contains("broke");
        assertThat(seen.out().contains("\u001b")).as("stdout carries escapes").isEqualTo(outTty);
        assertThat(seen.err().contains("\u001b")).as("stderr carries escapes").isEqualTo(errTty);
        assertThat(seen.liveOnStdout()).as("a live region on stdout").isEqualTo(outTty);
        assertThat(seen.liveOnStderr()).as("stderr may carry live output").isEqualTo(errTty);
    }
}
