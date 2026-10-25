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
    void the_default_is_integration_on(@TempDir Path tmp) {
        assertThat(JkM2Config.DEFAULTS.integration()).isTrue();
        assertThat(JkM2Config.fromToml(tmp.resolve("absent.toml"))).isEqualTo(JkM2Config.DEFAULTS);
    }

    @Test
    void the_lookup_can_be_switched_off(@TempDir Path tmp) throws Exception {
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nintegration = false\n")).integration())
                .isFalse();
    }

    @Test
    void env_overrides_the_file(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nintegration = true\n");

        JkM2Config c = JkM2Config.resolve(f, Map.of("JK_M2_INTEGRATION", "false")::get);

        assertThat(c.integration()).isFalse();
    }

    @Test
    void lookup_env_alias_still_overrides_integration(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nintegration = true\n");

        JkM2Config c = JkM2Config.resolve(f, Map.of("JK_M2_LOOKUP", "false")::get);

        assertThat(c.integration()).isFalse();
    }

    @Test
    void auto_clean_is_off_by_default_and_an_admin_turns_it_on_in_the_file_or_the_env(@TempDir Path tmp)
            throws Exception {
        assertThat(JkM2Config.DEFAULTS.autoClean()).isFalse();
        assertThat(JkM2Config.DEFAULTS.cleanAfterDays()).isEqualTo(60);

        JkM2Config file = JkM2Config.fromToml(toml(tmp, "[m2]\nauto-clean = true\nclean-after-days = 14\n"));
        assertThat(file.autoClean()).isTrue();
        assertThat(file.cleanAfterDays()).isEqualTo(14);

        Path f = toml(tmp, "[m2]\nauto-clean = false\nclean-after-days = 14\n");
        JkM2Config env = JkM2Config.resolve(f, Map.of("JK_M2_AUTO_CLEAN", "true", "JK_M2_CLEAN_AFTER_DAYS", "7")::get);
        assertThat(env.autoClean()).isTrue();
        assertThat(env.cleanAfterDays()).isEqualTo(7);
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
