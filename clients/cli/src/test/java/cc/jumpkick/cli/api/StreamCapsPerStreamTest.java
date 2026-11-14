// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.config.JkConfig;
import org.junit.jupiter.api.Test;
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

    /**
     * With only stdout redirected, stderr's chrome is what the stderr helpers render for it: the
     * failure wedge, the warning chip and the cancelled settle keep their colour on the terminal,
     * and any other line, composed for stdout's device, is plain.
     */
    @Test
    void with_stdout_redirected_only_stderr_chrome_keeps_its_colour() {
        assumeFalse(StreamCaps.forcedNonInteractive(), "TERM=dumb or CI turns ANSI off everywhere");
        String[] lines = new String[4];
        for (int i = 0; i < lines.length; i++) {
            int which = i;
            lines[i] = Capture.both(() -> {
                        try {
                            StreamCaps.asTerminal(
                                    false,
                                    true,
                                    () -> NoAnsi.withConfig(JkConfig.empty(), () -> {
                                        CliOutput.beginCommand(false);
                                        switch (which) {
                                            case 0 -> CommandWedge.printFail("Build", "broke");
                                            case 1 -> CommandWedge.printChipErr(Glyphs.BANG, "Build", "careful");
                                            case 2 -> CommandWedge.printCancelled("Build", null);
                                            default ->
                                                CliOutput.err(Theme.colorize(
                                                        "a note", Theme.active().darkGray()));
                                        }
                                        return null;
                                    }));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        } finally {
                            CliOutput.beginCommand(false);
                        }
                    })
                    .err();
        }
        assertThat(lines[0]).as("failure wedge").contains("broke").contains("\u001b");
        assertThat(lines[1]).as("warning chip").contains("careful").contains("\u001b");
        assertThat(lines[2]).as("cancelled settle").contains("cancelled").contains("\u001b");
        assertThat(lines[3]).as("any other stderr line").contains("a note").doesNotContain("\u001b");
    }
}
