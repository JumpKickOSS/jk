// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NerdFontDetectTest {

    @Test
    void env_override_true() {
        var r = NerdFontDetect.detect(Map.of("JK_NERDFONT", "true")::get);
        assertThat(r.nerdFont()).isTrue();
        assertThat(r.reason()).contains("JK_NERDFONT");
    }

    @Test
    void env_override_false() {
        var r = NerdFontDetect.detect(Map.of("JK_NERDFONT", "false", "TERM_PROGRAM", "iTerm.app")::get);
        assertThat(r.nerdFont()).isFalse();
    }

    @Test
    void ci_disables() {
        var r = NerdFontDetect.detect(Map.of("CI", "true", "TERM_PROGRAM", "iTerm.app")::get);
        assertThat(r.nerdFont()).isFalse();
        assertThat(r.reason()).contains("CI");
    }

    @Test
    void iterm_enables() {
        var r = NerdFontDetect.detect(Map.of("TERM_PROGRAM", "iTerm.app")::get);
        assertThat(r.nerdFont()).isTrue();
    }

    @Test
    void unknown_defaults_false() {
        // No font installed must be injected, not inherited from the machine running the suite:
        // fc-list finds 102 Nerd Fonts on a typical workstation and none on CI.
        var r = NerdFontDetect.detect(k -> null, () -> false);
        assertThat(r.nerdFont()).isFalse();
        assertThat(r.reason()).contains("unknown");
    }

    @Test
    void an_installed_nerd_font_enables_an_otherwise_unknown_terminal() {
        var r = NerdFontDetect.detect(k -> null, () -> true);
        assertThat(r.nerdFont()).isTrue();
        assertThat(r.reason()).contains("fc-list");
    }

    @Test
    void an_installed_nerd_font_does_not_override_an_explicit_no() {
        // The font list is a last-resort hint; every earlier signal outranks it.
        assertThat(NerdFontDetect.detect(Map.of("JK_NERDFONT", "false")::get, () -> true)
                        .nerdFont())
                .isFalse();
        assertThat(NerdFontDetect.detect(Map.of("CI", "true")::get, () -> true).nerdFont())
                .isFalse();
    }

    @Test
    void wt_session_enables() {
        var r = NerdFontDetect.detect(Map.of("WT_SESSION", "abc")::get);
        assertThat(r.nerdFont()).isTrue();
    }
}
