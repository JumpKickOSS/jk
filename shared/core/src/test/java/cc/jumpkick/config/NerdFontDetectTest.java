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
        var r = NerdFontDetect.detect(k -> null);
        assertThat(r.nerdFont()).isFalse();
        assertThat(r.reason()).contains("unknown");
    }

    @Test
    void wt_session_enables() {
        var r = NerdFontDetect.detect(Map.of("WT_SESSION", "abc")::get);
        assertThat(r.nerdFont()).isTrue();
    }
}
