// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.NerdFontDetect.Confidence;
import cc.jumpkick.config.TerminalIdentity.Terminal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Multiplexers, installed fonts, and the confirmation cache in {@code nerd-font = "auto"}. */
class NerdFontDetectCacheTest {

    private static final InstalledFonts NERD = () -> new InstalledFonts.Scan(NerdFontCaps.ALL, "nerd");
    private static final InstalledFonts PLAIN = () -> new InstalledFonts.Scan(NerdFontCaps.NONE, "plain");

    @Test
    void a_bundled_terminal_inside_tmux_still_gets_everything() {
        var r = detect(
                env("TMUX", "x", "TERM_PROGRAM", "tmux", "TERM", "tmux-256color", "GHOSTTY_RESOURCES_DIR", "/g"));
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
        assertThat(r.confidence()).isEqualTo(Confidence.KNOWN);
    }

    @Test
    void a_font_driven_terminal_inside_tmux_reads_its_font() {
        var fonts = fonts(Terminal.ITERM2, "MesloLGS NF 13");
        var r = NerdFontDetect.detect(
                env("TMUX", "x", "TERM_PROGRAM", "tmux", "ITERM_SESSION_ID", "w0t0p0")::get,
                fonts,
                PLAIN,
                TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void a_fallback_terminal_uses_an_installed_nerd_font_even_with_a_plain_configured_font() {
        var r = NerdFontDetect.detect(
                env("KONSOLE_VERSION", "230804")::get,
                fonts(Terminal.KONSOLE, "DejaVu Sans Mono"),
                NERD,
                TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
        assertThat(r.source()).isEqualTo("installed-fonts");
        assertThat(r.confidence()).isEqualTo(Confidence.INFERRED);
    }

    @Test
    void a_fallback_terminal_without_a_nerd_font_gets_nothing() {
        var r = NerdFontDetect.detect(
                env("VTE_VERSION", "7402")::get, TerminalFonts.NONE, PLAIN, TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void alacritty_keeps_its_floor_and_gains_the_pills_from_installed_fonts() {
        var r = NerdFontDetect.detect(
                env("ALACRITTY_LOG", "x")::get, TerminalFonts.NONE, NERD, TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.ALL);
    }

    @Test
    void an_unidentified_terminal_with_an_installed_nerd_font_gets_only_the_triangles() {
        var r = NerdFontDetect.detect(env()::get, TerminalFonts.NONE, NERD, TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        assertThat(r.confidence()).isEqualTo(Confidence.INFERRED);
    }

    @Test
    void font_driven_terminals_ignore_installed_fonts() {
        var r = NerdFontDetect.detect(
                env("TERM_PROGRAM", "iTerm.app")::get,
                fonts(Terminal.ITERM2, "Monaco 12"),
                NERD,
                TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void a_confirmation_overrides_the_heuristics_until_something_changes(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        Map<String, String> env = env("TERM_PROGRAM", "Apple_Terminal");
        var fonts = TerminalFonts.NONE;

        assertThat(NerdFontDetect.detect(env::get, fonts, PLAIN, cache).caps()).isEqualTo(NerdFontCaps.NONE);
        assertThat(NerdFontDetect.confirm(env::get, fonts, PLAIN, cache, NerdFontCaps.WEDGE_ONLY, "confirmed"))
                .isTrue();

        var confirmed = NerdFontDetect.detect(env::get, fonts, PLAIN, cache);
        assertThat(confirmed.caps()).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        assertThat(confirmed.confidence()).isEqualTo(Confidence.CONFIRMED);
        assertThat(confirmed.stale()).isNull();

        // A font gets installed: the answer no longer holds, detection falls back and says why.
        var stale = NerdFontDetect.detect(env::get, fonts, NERD, cache);
        assertThat(stale.caps()).isEqualTo(NerdFontCaps.NONE);
        var staleness = stale.stale();
        assertThat(staleness).isNotNull();
        assertThat(Objects.requireNonNull(staleness).changed()).containsExactly("installed fonts");
        assertThat(stale.cache()).contains("installed fonts");
        assertThat(NerdFontDetect.claimStaleHint(stale, cache)).isTrue();
        assertThat(NerdFontDetect.claimStaleHint(stale, cache)).isFalse();
    }

    @Test
    void confirming_in_one_terminal_does_not_answer_for_another(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        NerdFontDetect.confirm(
                env("TERM_PROGRAM", "Apple_Terminal")::get,
                TerminalFonts.NONE,
                PLAIN,
                cache,
                NerdFontCaps.ALL,
                "confirmed");
        var other = NerdFontDetect.detect(env("TERM_PROGRAM", "Hyper")::get, TerminalFonts.NONE, PLAIN, cache);
        assertThat(other.caps()).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void a_remote_session_can_be_confirmed_for_its_client(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        Map<String, String> env = env("SSH_CONNECTION", "10.0.0.7 5000 10.0.0.1 22");
        assertThat(NerdFontDetect.detect(env::get, TerminalFonts.NONE, PLAIN, cache)
                        .caps())
                .isEqualTo(NerdFontCaps.NONE);
        NerdFontDetect.confirm(env::get, TerminalFonts.NONE, PLAIN, cache, NerdFontCaps.ALL, "confirmed");
        assertThat(NerdFontDetect.detect(env::get, TerminalFonts.NONE, PLAIN, cache)
                        .caps())
                .isEqualTo(NerdFontCaps.ALL);
        Map<String, String> otherClient = env("SSH_CONNECTION", "10.0.0.8 5000 10.0.0.1 22");
        assertThat(NerdFontDetect.detect(otherClient::get, TerminalFonts.NONE, PLAIN, cache)
                        .caps())
                .isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void ci_beats_a_confirmation(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        NerdFontDetect.confirm(env()::get, TerminalFonts.NONE, PLAIN, cache, NerdFontCaps.ALL, "confirmed");
        assertThat(NerdFontDetect.detect(env("CI", "true")::get, TerminalFonts.NONE, PLAIN, cache)
                        .caps())
                .isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void a_broken_installed_font_scan_costs_only_the_upgrade() {
        InstalledFonts boom = () -> {
            throw new IllegalStateException("boom");
        };
        var r = NerdFontDetect.detect(env("VTE_VERSION", "7")::get, TerminalFonts.NONE, boom, TerminalCapsCache.none());
        assertThat(r.caps()).isEqualTo(NerdFontCaps.NONE);
    }

    private static NerdFontDetect.Result detect(Map<String, String> env) {
        return NerdFontDetect.detect(env::get, TerminalFonts.NONE, PLAIN, TerminalCapsCache.none());
    }

    private static TerminalFonts fonts(Terminal t, String font) {
        return terminal -> terminal == t ? TerminalFonts.Lookup.of(font) : TerminalFonts.Lookup.EMPTY;
    }

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
