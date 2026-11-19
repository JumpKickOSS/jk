// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.InstallDestination;
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
    void the_default_is_the_private_repository(@TempDir Path tmp) {
        assertThat(JkInstallConfig.DEFAULTS.destination()).isEqualTo(InstallDestination.JK);
        assertThat(JkInstallConfig.fromToml(tmp.resolve("absent.toml"))).isEqualTo(JkInstallConfig.DEFAULTS);
    }

    /** Only a machine that shares its downloads with Maven installs into the local repository unasked. */
    @Test
    void sharing_with_maven_makes_the_local_repository_the_default(@TempDir Path tmp) throws Exception {
        Path share = toml(tmp, "[m2]\nintegration = \"share\"\n");
        assertThat(JkInstallConfig.fromToml(share).destination()).isEqualTo(InstallDestination.M2);
        assertThat(JkInstallConfig.resolve(share, Map.of("JK_INSTALL_DESTINATION", "jk")::get)
                        .destination())
                .isEqualTo(InstallDestination.JK);

        Path absent = tmp.resolve("absent.toml");
        assertThat(JkInstallConfig.resolve(absent, Map.of("JK_M2_INTEGRATION", "share")::get)
                        .destination())
                .isEqualTo(InstallDestination.M2);
        assertThat(JkInstallConfig.resolve(absent, Map.of("JK_M2_INTEGRATION", "read")::get)
                        .destination())
                .isEqualTo(InstallDestination.JK);
        assertThat(JkInstallConfig.resolve(absent, Map.of("JK_INSTALL_DESTINATION", "m2")::get)
                        .destination())
                .isEqualTo(InstallDestination.M2);
    }

    @Test
    void the_file_can_choose_the_private_repository(@TempDir Path tmp) throws Exception {
        assertThat(JkInstallConfig.fromToml(toml(tmp, "[install]\ndestination = \"jk\"\n"))
                        .destination())
                .isEqualTo(InstallDestination.JK);
    }

    @Test
    void the_environment_beats_the_file(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[install]\ndestination = \"jk\"\n");

        assertThat(JkInstallConfig.resolve(f, Map.of("JK_INSTALL_DESTINATION", "m2")::get)
                        .destination())
                .isEqualTo(InstallDestination.M2);
    }

    @Test
    void an_unknown_word_falls_through_to_the_layer_below(@TempDir Path tmp) throws Exception {
        Path f = toml(tmp, "[install]\ndestination = \"jk\"\n");

        assertThat(JkInstallConfig.resolve(f, Map.of("JK_INSTALL_DESTINATION", "shelf")::get)
                        .destination())
                .isEqualTo(InstallDestination.JK);
        assertThat(JkInstallConfig.fromToml(toml(tmp, "[install]\ndestination = \"nowhere\"\n"))
                        .destination())
                .isEqualTo(InstallDestination.JK);
    }
}
