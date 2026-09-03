// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Tier order and outcomes for {@code nerd-font = "auto"}.
 *
 * <p>Every case injects both the environment and the font sources, so nothing here depends on the
 * terminal or the config files of the machine running the suite. That is deliberate: the previous
 * probe shelled out to {@code fc-list}, which made "an unknown terminal defaults to off" pass on CI
 * and fail on any workstation with a Nerd Font installed — precisely backwards.
 */
class NerdFontDetectTest {

    // ── T0: gates ───────────────────────────────────────────────────────────

    @Test
    void ci_never_gets_pua() {
        assertThat(detect(env("CI", "true")).caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(detect(env("CI", "1")).caps()).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void ci_beats_an_otherwise_capable_terminal() {
        var r = detect(env("CI", "true", "TERM_PROGRAM", "ghostty"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(r.source()).isEqualTo("ci");
    }

    @Test
    void dumb_terminal_never_gets_pua() {
        assertThat(detect(env("TERM", "dumb")).caps()).isEqualTo(NerdFontCaps.NONE);
    }

    // ── T1: terminals with built-in glyph coverage ──────────────────────────

    @Test
    void ghostty_kitty_and_wezterm_get_everything() {
        assertThat(detect(env("TERM_PROGRAM", "ghostty")).caps()).isEqualTo(NerdFontCaps.ALL);
        assertThat(detect(env("TERM_PROGRAM", "kitty")).caps()).isEqualTo(NerdFontCaps.ALL);
        assertThat(detect(env("TERM_PROGRAM", "WezTerm")).caps()).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void ghostty_and_kitty_are_also_recognised_by_term() {
        // TERM_PROGRAM is not always propagated; TERM usually is.
        assertThat(detect(env("TERM", "xterm-ghostty")).caps()).isEqualTo(NerdFontCaps.ALL);
        assertThat(detect(env("TERM", "xterm-kitty")).caps()).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void windows_terminal_gets_everything_without_reading_a_font() {
        // builtinGlyphs draws U+E0B0-U+E0BF from an internal vector table, ignoring the configured
        // font — and that range covers all four of our codepoints.
        var r = detect(env("WT_SESSION", "d3adb33f-0000-0000-0000-000000000000"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
        assertThat(r.source()).isEqualTo("builtin-glyphs");
    }

    @Test
    void windows_terminal_is_detected_from_inside_wsl() {
        // WT forwards WT_SESSION through WSLENV, so the WSL shell sees it too.
        var r = detect(env("WT_SESSION", "abc", "WSL_DISTRO_NAME", "Ubuntu", "TERM", "xterm-256color"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
    }

    // ── T2: SSH ─────────────────────────────────────────────────────────────

    @Test
    void ssh_disables_because_the_font_lives_on_the_client() {
        assertThat(detect(env("SSH_TTY", "/dev/ttys001")).caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(detect(env("SSH_CONNECTION", "10.0.0.1 22 10.0.0.2 22")).caps())
                .isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void a_forwarded_capable_term_beats_the_ssh_gate() {
        // Ordered after T1 on purpose: if TERM says ghostty, the client IS ghostty.
        var r = detect(env("SSH_TTY", "/dev/ttys001", "TERM", "xterm-ghostty"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void ssh_beats_a_local_config_file() {
        // The local iTerm2 prefs describe this host, not the client that is actually drawing.
        var r = detect(env("SSH_TTY", "/dev/ttys001", "TERM_PROGRAM", "iTerm.app"), fonts -> "MesloLGS NF");
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(r.source()).isEqualTo("remote-session");
    }

    // ── T3: identified terminals ────────────────────────────────────────────

    @Test
    void apple_terminal_is_pinned_off() {
        // Not WEDGE_ONLY: a fresh macOS install renders U+E0B0 as tofu in Terminal.app, and its
        // font is a blob we do not decode, so there is nothing to upgrade from.
        var r = detect(env("TERM_PROGRAM", "Apple_Terminal"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(r.source()).isEqualTo("apple-terminal");
    }

    @Test
    void iterm_resolves_from_its_configured_font() {
        assertThat(iterm("JetBrainsMonoNFM-Regular 12")).isEqualTo(NerdFontCaps.ALL);
        assertThat(iterm("MesloLGS NF 13")).isEqualTo(NerdFontCaps.ALL);
        assertThat(iterm("Monaco 12")).isEqualTo(NerdFontCaps.NONE);
        assertThat(iterm("Cascadia Mono PL 12")).isEqualTo(NerdFontCaps.WEDGE_ONLY);
    }

    @Test
    void a_terminal_whose_font_cannot_be_read_gets_nothing() {
        var r = detect(env("TERM_PROGRAM", "iTerm.app"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(r.reason()).contains("not determined");
    }

    @Test
    void alacritty_is_identified_by_any_of_its_three_markers() {
        for (String marker : new String[] {"ALACRITTY_LOG", "ALACRITTY_WINDOW_ID", "ALACRITTY_SOCKET"}) {
            var r = detect(env(marker, "x"), new Fonts().alacritty("FiraCode Nerd Font"));
            assertThat(r.caps()).as(marker).isEqualTo(NerdFontCaps.ALL);
            assertThat(r.source()).as(marker).isEqualTo("alacritty");
        }
    }

    @Test
    void alacritty_floors_at_the_wedge_it_draws_itself() {
        // Alacritty renders the classic Powerline set out of its own tables, so the triangles are
        // safe whatever the font says. A plain font, an unreadable alacritty.toml, and a Powerline
        // patch all land on that floor.
        assertThat(alacritty("JetBrains Mono")).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        assertThat(alacritty("Cascadia Mono PL")).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        assertThat(detect(env("ALACRITTY_LOG", "x")).caps()).isEqualTo(NerdFontCaps.WEDGE_ONLY);
    }

    @Test
    void alacritty_still_upgrades_to_everything_from_its_font() {
        // The floor must not short-circuit the lookup: Powerline Extra is not built in, so the
        // semi-circles are still earned from the configured font.
        assertThat(alacritty("MesloLGS NF")).isEqualTo(NerdFontCaps.ALL);
        assertThat(alacritty("JetBrainsMonoNFM-Regular")).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void vscode_and_zed_resolve_from_their_settings() {
        assertThat(detect(env("TERM_PROGRAM", "vscode"), new Fonts().vscode("Hack Nerd Font Mono"))
                        .caps())
                .isEqualTo(NerdFontCaps.ALL);
        assertThat(detect(env("TERM_PROGRAM", "zed"), new Fonts().zed("Menlo")).caps())
                .isEqualTo(NerdFontCaps.NONE);
    }

    // ── T4: unknown ─────────────────────────────────────────────────────────

    @Test
    void an_unidentified_terminal_defaults_off() {
        var r = detect(env());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(r.source()).isEqualTo("unknown-terminal");
    }

    @Test
    void a_known_but_unsupported_terminal_defaults_off_and_says_so() {
        var r = detect(env("TERM_PROGRAM", "Hyper"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(r.source()).isEqualTo("no-resolver");
        assertThat(r.reason()).contains("hyper");
    }

    @Test
    void every_result_carries_a_source_and_a_reason() {
        // setup-terminal --explain prints these, so an empty one is a user-visible bug.
        for (Map<String, String> e : List.of(
                env("CI", "true"),
                env("TERM", "dumb"),
                env("TERM_PROGRAM", "ghostty"),
                env("WT_SESSION", "x"),
                env("SSH_TTY", "x"),
                env("TERM_PROGRAM", "Apple_Terminal"),
                env("TERM_PROGRAM", "iTerm.app"),
                env("ALACRITTY_LOG", "x"),
                env("TERM_PROGRAM", "Hyper"),
                env())) {
            var r = detect(e);
            assertThat(r.source()).as("source for %s", e).isNotBlank();
            assertThat(r.reason()).as("reason for %s", e).isNotBlank();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static NerdFontCaps iterm(String font) {
        return detect(env("TERM_PROGRAM", "iTerm.app"), new Fonts().iterm(font)).caps();
    }

    private static NerdFontCaps alacritty(String font) {
        return detect(env("ALACRITTY_LOG", "x"), new Fonts().alacritty(font)).caps();
    }

    private static NerdFontDetect.Result detect(Map<String, String> env) {
        return detect(env, TerminalFonts.NONE);
    }

    private static NerdFontDetect.Result detect(Map<String, String> env, TerminalFonts fonts) {
        return NerdFontDetect.detect(lookup(env), fonts);
    }

    /** Convenience for the single-source cases: one font answered for every terminal. */
    private static NerdFontDetect.Result detect(Map<String, String> env, Function<Void, String> anyFont) {
        String f = anyFont.apply(null);
        return detect(env, new Fonts().iterm(f).alacritty(f).vscode(f).zed(f));
    }

    private static Function<String, String> lookup(Map<String, String> env) {
        return env::get;
    }

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Injectable font sources — one setter per terminal, unset reads as absent. */
    private static final class Fonts implements TerminalFonts {
        private String iterm;
        private String alacritty;
        private String vscode;
        private String zed;

        Fonts iterm(String f) {
            this.iterm = f;
            return this;
        }

        Fonts alacritty(String f) {
            this.alacritty = f;
            return this;
        }

        Fonts vscode(String f) {
            this.vscode = f;
            return this;
        }

        Fonts zed(String f) {
            this.zed = f;
            return this;
        }

        @Override
        public Optional<String> itermFont() {
            return Optional.ofNullable(iterm);
        }

        @Override
        public Optional<String> alacrittyFont() {
            return Optional.ofNullable(alacritty);
        }

        @Override
        public Optional<String> vscodeFont() {
            return Optional.ofNullable(vscode);
        }

        @Override
        public Optional<String> zedFont() {
            return Optional.ofNullable(zed);
        }
    }
}
