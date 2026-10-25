// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.InstallTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkInstallConfigTest {

    private static Path toml(Path dir, String body) throws Exception {
        Path f = dir.resolve("config.toml");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void the_default_is_the_maven_local_repository(@TempDir Path tmp) {
        assertThat(JkInstallConfig.DEFAULTS.target()).isEqualTo(InstallTarget.M2);
        assertThat(JkInstallConfig.fromToml(tmp.resolve("absent.toml"))).isEqualTo(JkInstallConfig.DEFAULTS);
    }

    @Test
    void the_file_can_choose_the_private_repository(@TempDir Path tmp) throws Exception {
        assertThat(JkInstallConfig.fromToml(toml(tmp, "[install]\ntarget = \"jk\"\n"))
                        .target())
                .isEqualTo(InstallTarget.JK);
    }

    @Test
    void the_environment_beats_the_file(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[install]\ntarget = \"jk\"\n");

        assertThat(JkInstallConfig.resolve(f, Map.of("JK_INSTALL_TARGET", "m2")::get)
                        .target())
                .isEqualTo(InstallTarget.M2);
    }

    @Test
    void an_unknown_word_falls_through_to_the_layer_below(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[install]\ntarget = \"jk\"\n");

        assertThat(JkInstallConfig.resolve(f, Map.of("JK_INSTALL_TARGET", "shelf")::get)
                        .target())
                .isEqualTo(InstallTarget.JK);
        assertThat(JkInstallConfig.fromToml(toml(tmp, "[install]\ntarget = \"nowhere\"\n"))
                        .target())
                .isEqualTo(InstallTarget.M2);
    }
}
