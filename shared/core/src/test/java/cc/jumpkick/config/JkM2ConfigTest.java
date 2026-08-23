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
    void the_default_is_integration_and_install_on(@TempDir Path tmp) {
        assertThat(JkM2Config.DEFAULTS.integration()).isTrue();
        assertThat(JkM2Config.DEFAULTS.install()).isTrue();
        assertThat(JkM2Config.fromToml(tmp.resolve("absent.toml"))).isEqualTo(JkM2Config.DEFAULTS);
    }

    @Test
    void the_lookup_can_be_switched_off(@TempDir Path tmp) throws Exception {
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nintegration = false\n")).integration())
                .isFalse();
    }

    @Test
    void env_overrides_the_file(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nintegration = true\ninstall = true\n");

        JkM2Config c = JkM2Config.resolve(f, Map.of("JK_M2_INTEGRATION", "false", "JK_M2_INSTALL", "false")::get);

        assertThat(c.integration()).isFalse();
        assertThat(c.install()).isFalse();
    }

    @Test
    void lookup_env_alias_still_overrides_integration(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nintegration = true\n");

        JkM2Config c = JkM2Config.resolve(f, Map.of("JK_M2_LOOKUP", "false")::get);

        assertThat(c.integration()).isFalse();
    }

    @Test
    void install_can_be_off_while_integration_stays_on(@TempDir Path tmp) throws Exception {
        JkM2Config c = JkM2Config.fromToml(toml(tmp, "[m2]\ninstall = false\n"));
        assertThat(c.install()).isFalse();
        assertThat(c.integration()).isTrue();
    }

    @Test
    void a_malformed_value_falls_back_rather_than_failing_a_build(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nintegration = \"yes please\"\n");

        assertThat(JkM2Config.fromToml(f)).isEqualTo(JkM2Config.DEFAULTS);
    }

    @Test
    void an_unrelated_table_is_ignored(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "nerd-font = true\n\n[cache]\nauto-prune = false\n");

        assertThat(JkM2Config.fromToml(f)).isEqualTo(JkM2Config.DEFAULTS);
    }
}
