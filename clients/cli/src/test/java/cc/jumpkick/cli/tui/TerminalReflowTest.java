// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Env-only reflow classification. Misreading a reflowing terminal as clipping only under-wipes on
 * shrink (stale orphan rows); the reverse would erase completed scrollback — so the default must
 * stay clipping and additions must key on signals the terminal actually exports.
 */
class TerminalReflowTest {

    private static boolean detect(Map<String, String> env) {
        return TerminalReflow.detect(env::get);
    }

    @Test
    void vte_and_windows_terminal_reflow() {
        assertThat(detect(Map.of("VTE_VERSION", "7402"))).isTrue();
        assertThat(detect(Map.of("WT_SESSION", "b1f0"))).isTrue();
    }

    @Test
    void konsole_reflows_via_its_version_export() {
        // Konsole sets KONSOLE_VERSION and no TERM_PROGRAM; TERM is a plain xterm-256color.
        assertThat(detect(Map.of("KONSOLE_VERSION", "230804", "TERM", "xterm-256color")))
                .isTrue();
    }

    @Test
    void alacritty_reflows_via_term_or_term_program() {
        assertThat(detect(Map.of("TERM", "alacritty"))).isTrue();
        assertThat(detect(Map.of("TERM", "alacritty-direct"))).isTrue();
        assertThat(detect(Map.of("TERM_PROGRAM", "alacritty", "TERM", "xterm-256color")))
                .isTrue();
    }

    @Test
    void term_program_identities_reflow() {
        for (String p : new String[] {"ghostty", "kitty", "WezTerm", "iTerm.app", "Apple_Terminal", "vscode"}) {
            assertThat(detect(Map.of("TERM_PROGRAM", p))).isTrue();
        }
        assertThat(detect(Map.of("TERM", "xterm-kitty"))).isTrue();
        assertThat(detect(Map.of("TERM", "foot"))).isTrue();
    }

    @Test
    void unknown_and_clipping_terminals_stay_clipping() {
        assertThat(detect(Map.of())).isFalse();
        assertThat(detect(Map.of("TERM", "xterm-256color"))).isFalse();
        assertThat(detect(Map.of("TERM", "linux"))).isFalse();
        assertThat(detect(Map.of("TERM", "screen-256color"))).isFalse();
        assertThat(detect(Map.of("KONSOLE_VERSION", " "))).isFalse(); // blank export is no signal
    }
}
