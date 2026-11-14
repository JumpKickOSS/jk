// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.JkConfig;
import org.junit.jupiter.api.Test;

/**
 * A stream that is not a terminal is plain; a force switch keeps colour but never a live region;
 * {@code --no-ansi} beats everything; each stream is judged on its own device.
 */
class StreamCapsTest {

    private static boolean ansi(boolean outTty, JkConfig config, StreamCaps.Side side) throws Exception {
        return StreamCaps.asTerminal(outTty, outTty, () -> NoAnsi.withConfig(config, () -> StreamCaps.ansi(side)));
    }

    @Test
    void a_redirected_stream_is_plain_and_a_terminal_is_not() throws Exception {
        assumeFalse(StreamCaps.forcedNonInteractive(), "TERM=dumb or CI turns ANSI off everywhere");
        assertThat(ansi(false, JkConfig.empty(), StreamCaps.Side.OUT)).isFalse();
        assertThat(ansi(true, JkConfig.empty(), StreamCaps.Side.OUT)).isTrue();
    }

    @Test
    void a_force_switch_keeps_colour_on_a_redirect_but_never_a_live_region() throws Exception {
        for (JkConfig forced : new JkConfig[] {
            JkConfig.empty().withForceAnsi(true), JkConfig.empty().withColor(JkConfig.ColorChoice.ALWAYS)
        }) {
            assertThat(ansi(false, forced, StreamCaps.Side.OUT)).isTrue();
            assertThat(StreamCaps.asTerminal(
                            false, false, () -> NoAnsi.withConfig(forced, () -> StreamCaps.live(StreamCaps.Side.OUT))))
                    .isFalse();
        }
    }

    @Test
    void no_ansi_beats_a_terminal_and_a_force_switch() throws Exception {
        JkConfig both = JkConfig.empty().withNoAnsi(true).withForceAnsi(true);
        assertThat(ansi(true, both, StreamCaps.Side.OUT)).isFalse();
    }

    @Test
    void a_redirected_stderr_is_stripped_of_what_stdout_s_decision_let_in() throws Exception {
        assumeFalse(StreamCaps.forcedNonInteractive(), "TERM=dumb or CI turns ANSI off everywhere");
        Capture.Streams streams = Capture.both(() -> {
            try {
                StreamCaps.asTerminal(
                        true,
                        false,
                        () -> NoAnsi.withConfig(JkConfig.empty(), () -> {
                            String painted = Theme.active().success().render("ok ✓");
                            CliOutput.beginCommand(false);
                            CliOutput.out(painted);
                            CliOutput.err(painted);
                            return null;
                        }));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                CliOutput.beginCommand(false);
            }
        });
        assertThat(streams.out()).as("the terminal keeps its colour").contains("\u001b[");
        assertThat(streams.err()).as("the error log").doesNotContain("\u001b").contains("ok");
    }

    @Test
    void the_help_into_a_redirect_carries_no_escape_byte() {
        // The test JVM's stdout is a pipe, as it is for jk --help > f.
        String help = Capture.stdout(() -> Jk.execute("--help"));
        assertThat(help).contains("Usage").doesNotContain("\u001b");
    }
}
