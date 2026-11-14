// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.terminal.Ansi;
import org.junit.jupiter.api.Test;

/** OSC sequences ask {@link StreamCaps}: none under {@code --no-ansi}, no hyperlink into a redirect. */
class OscGateTest {

    @Test
    void no_ansi_implies_no_osc_on_a_terminal() throws Exception {
        JkConfig both = JkConfig.empty().withNoAnsi(true).withForceAnsi(true);
        assertThat(StreamCaps.asTerminal(true, true, () -> NoAnsi.withConfig(both, Osc::oscEnabled)))
                .as("--no-ansi implies no OSC")
                .isFalse();
    }

    @Test
    void a_hyperlink_into_a_redirect_is_its_text_alone() throws Exception {
        String link = StreamCaps.asTerminal(
                false, false, () -> NoAnsi.withConfig(JkConfig.empty(), () -> Osc.hyperlink("http://x", "x")));
        assertThat(link).isEqualTo("x");
        String forced = StreamCaps.asTerminal(
                false,
                false,
                () -> NoAnsi.withConfig(JkConfig.empty().withForceAnsi(true), () -> Osc.hyperlink("http://x", "x")));
        assertThat(forced).as("a forced link survives for less -R").isEqualTo(Ansi.hyperlink("http://x", "x"));
    }
}
