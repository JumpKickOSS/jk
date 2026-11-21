// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void the_default_reads_the_local_repository_and_downloads_into_the_store(@TempDir Path tmp) {
        assertThat(JkM2Config.DEFAULTS.mode()).isEqualTo(JkM2Config.Mode.READ);
        assertThat(JkM2Config.DEFAULTS.share()).isFalse();
    }

    @Test
    void the_file_and_the_environment_name_a_mode(@TempDir Path tmp) throws Exception {
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nintegration = \"share\"\n"))
                        .mode())
                .isEqualTo(JkM2Config.Mode.SHARE);
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nintegration = \"off\"\n"))
                        .integration())
                .isFalse();
        Path read = toml(tmp, "[m2]\nintegration = \"read\"\n");
        assertThat(JkM2Config.fromToml(read).mode()).isEqualTo(JkM2Config.Mode.READ);
        assertThat(JkM2Config.resolve(read, Map.of("JK_M2_INTEGRATION", "share")::get)
                        .share())
                .isTrue();
        assertThat(JkM2Config.resolve(read, Map.of("JK_M2_INTEGRATION", "off")::get)
                        .mode())
                .isEqualTo(JkM2Config.Mode.OFF);
    }

    /** A boolean was the whole setting before the modes had names, and {@code true} then wrote downloads through. */
    @Test
    void a_boolean_keeps_what_it_meant(@TempDir Path tmp) throws Exception {
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nintegration = true\n")).mode())
                .isEqualTo(JkM2Config.Mode.SHARE);
        assertThat(JkM2Config.fromToml(toml(tmp, "[m2]\nintegration = false\n")).mode())
                .isEqualTo(JkM2Config.Mode.OFF);
        Path absent = tmp.resolve("absent.toml");
        assertThat(JkM2Config.resolve(absent, Map.of("JK_M2_INTEGRATION", "true")::get)
                        .mode())
                .isEqualTo(JkM2Config.Mode.SHARE);
        assertThat(JkM2Config.resolve(absent, Map.of("JK_M2_INTEGRATION", "0")::get)
                        .mode())
                .isEqualTo(JkM2Config.Mode.OFF);
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
    void the_pre_rename_lookup_name_is_not_read(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[m2]\nintegration = true\n");

        JkM2Config c = JkM2Config.resolve(f, Map.of("JK_M2_LOOKUP", "false")::get);

        assertThat(c.mode()).isEqualTo(JkM2Config.Mode.SHARE);
    }

    /**
     * The documented ladder is the whole ladder: no {@code jk.m2.*} JVM property names a setting,
     * and a test varies one through the {@code jk.env.*} seam every machine setting reads.
     */
    @Test
    void a_jvm_property_names_no_m2_setting_and_the_env_seam_does(@TempDir Path tmp) {
        System.setProperty("jk.env.JK_HOME", tmp.resolve("home").toString());
        try {
            System.setProperty("jk.m2.integration", "off");
            System.setProperty("jk.m2.lookup", "off");
            System.setProperty("jk.m2.auto-clean", "true");
            assertThat(JkM2Config.resolve()).isEqualTo(JkM2Config.DEFAULTS);

            System.setProperty("jk.env.JK_M2_INTEGRATION", "off");
            System.setProperty("jk.env.JK_M2_AUTO_CLEAN", "true");
            System.setProperty("jk.env.JK_M2_CLEAN_AFTER_DAYS", "7");
            assertThat(JkM2Config.resolve()).isEqualTo(new JkM2Config(JkM2Config.Mode.OFF, true, 7));
        } finally {
            for (String name : List.of(
                    "jk.env.JK_HOME",
                    "jk.m2.integration",
                    "jk.m2.lookup",
                    "jk.m2.auto-clean",
                    "jk.env.JK_M2_INTEGRATION",
                    "jk.env.JK_M2_AUTO_CLEAN",
                    "jk.env.JK_M2_CLEAN_AFTER_DAYS")) {
                System.clearProperty(name);
            }
        }
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
