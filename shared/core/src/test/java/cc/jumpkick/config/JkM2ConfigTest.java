// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkM2ConfigTest {

    private static Path toml(Path dir, String body) throws Exception {
        Path f = dir.resolve("config.toml");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void the_default_is_lookup_on(@TempDir Path tmp) {
        assertThat(JkM2Config.DEFAULTS.enabled()).isTrue();
        assertThat(JkM2Config.fromToml(tmp.resolve("absent.toml"))).isEqualTo(JkM2Config.DEFAULTS);
    }

    @Test
    void the_lookup_can_be_switched_off(@TempDir Path tmp) throws Exception {
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nenabled = false\n")).enabled())
                .isFalse();
    }

    @Test
    void env_overrides_the_file(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nenabled = true\n");

        JkM2Config c = JkM2Config.resolve(f, Map.of("JK_M2_LOOKUP", "false")::get);

        assertThat(c.enabled()).isFalse();
    }

    @Test
    void a_malformed_value_falls_back_rather_than_failing_a_build(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nenabled = \"yes please\"\n");

        assertThat(JkM2Config.fromToml(f)).isEqualTo(JkM2Config.DEFAULTS);
    }

    @Test
    void an_unrelated_table_is_ignored(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "nerd-font = true\n\n[cache]\nauto-prune = false\n");

        assertThat(JkM2Config.fromToml(f)).isEqualTo(JkM2Config.DEFAULTS);
    }
}
