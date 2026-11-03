// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TerminalIdentity.Terminal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalIdentityTest {

    @Test
    void term_program_names_the_terminal_and_carries_its_version() {
        var id = of("TERM_PROGRAM", "iTerm.app", "TERM_PROGRAM_VERSION", "3.5.1");
        assertThat(id.terminal()).isEqualTo(Terminal.ITERM2);
        assertThat(id.version()).isEqualTo("3.5.1");
        assertThat(id.multiplexed()).isFalse();
        assertThat(id.remote()).isFalse();
    }

    @Test
    void tmux_hides_term_program_but_not_the_terminal_variables() {
        var ghostty = of("TMUX", "/tmp/tmux-1/default,1,0", "TERM_PROGRAM", "tmux", "GHOSTTY_RESOURCES_DIR", "/x");
        assertThat(ghostty.terminal()).isEqualTo(Terminal.GHOSTTY);
        assertThat(ghostty.multiplexed()).isTrue();
        assertThat(of("TERM_PROGRAM", "tmux", "KITTY_WINDOW_ID", "3").terminal())
                .isEqualTo(Terminal.KITTY);
        assertThat(of("TERM_PROGRAM", "tmux", "WEZTERM_PANE", "0").terminal()).isEqualTo(Terminal.WEZTERM);
        assertThat(of("TERM_PROGRAM", "tmux", "ITERM_SESSION_ID", "w0t0p0").terminal())
                .isEqualTo(Terminal.ITERM2);
        assertThat(of("TERM_PROGRAM", "tmux", "ALACRITTY_SOCKET", "/s").terminal())
                .isEqualTo(Terminal.ALACRITTY);
    }

    @Test
    void screen_and_zellij_count_as_multiplexers() {
        assertThat(of("STY", "123.pts-0.host").multiplexed()).isTrue();
        assertThat(of("ZELLIJ", "0").multiplexed()).isTrue();
        assertThat(of("TERM", "screen-256color").multiplexed()).isTrue();
        assertThat(of("TERM", "xterm-256color").multiplexed()).isFalse();
    }

    @Test
    void an_explicit_term_program_outranks_inherited_markers() {
        // VS Code launched from Ghostty inherits GHOSTTY_RESOURCES_DIR but sets TERM_PROGRAM itself.
        var id = of("TERM_PROGRAM", "vscode", "GHOSTTY_RESOURCES_DIR", "/x");
        assertThat(id.terminal()).isEqualTo(Terminal.VSCODE);
    }

    @Test
    void linux_terminals_are_recognised_from_their_exports() {
        assertThat(of("KONSOLE_VERSION", "230804").terminal()).isEqualTo(Terminal.KONSOLE);
        assertThat(of("KONSOLE_VERSION", "230804").version()).isEqualTo("230804");
        assertThat(of("VTE_VERSION", "7402").terminal()).isEqualTo(Terminal.VTE);
        assertThat(of("TERM", "foot").terminal()).isEqualTo(Terminal.FOOT);
        assertThat(of("TERM", "foot-direct").terminal()).isEqualTo(Terminal.FOOT);
    }

    @Test
    void blank_exports_are_no_signal() {
        assertThat(of("KONSOLE_VERSION", " ", "WT_SESSION", "").terminal()).isEqualTo(Terminal.UNKNOWN);
    }

    @Test
    void a_remote_session_trusts_only_what_ssh_forwards() {
        // The marker variables describe the server's terminal, not the client's.
        var local = of("SSH_TTY", "/dev/pts/1", "TERM_PROGRAM", "iTerm.app", "KITTY_WINDOW_ID", "1");
        assertThat(local.remote()).isTrue();
        assertThat(local.terminal()).isEqualTo(Terminal.UNKNOWN);
        assertThat(of("SSH_CONNECTION", "1 2 3 4", "TERM", "xterm-ghostty").terminal())
                .isEqualTo(Terminal.GHOSTTY);
        var iterm = of("SSH_TTY", "x", "LC_TERMINAL", "iTerm2", "LC_TERMINAL_VERSION", "3.5.1");
        assertThat(iterm.terminal()).isEqualTo(Terminal.ITERM2);
        assertThat(iterm.version()).isEqualTo("3.5.1");
    }

    @Test
    void windows_terminal_is_identified_by_its_session_variable() {
        assertThat(of("WT_SESSION", "abc").terminal()).isEqualTo(Terminal.WINDOWS_TERMINAL);
    }

    @Test
    void nothing_identifies_nothing() {
        assertThat(of().terminal()).isEqualTo(Terminal.UNKNOWN);
    }

    private static TerminalIdentity of(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return TerminalIdentity.of(m::get);
    }
}
