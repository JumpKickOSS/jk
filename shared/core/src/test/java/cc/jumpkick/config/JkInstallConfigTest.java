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
    void the_default_is_the_maven_local_repository(@TempDir Path tmp) {
        assertThat(JkInstallConfig.DEFAULTS.destination()).isEqualTo(InstallDestination.M2);
        assertThat(JkInstallConfig.fromToml(tmp.resolve("absent.toml"))).isEqualTo(JkInstallConfig.DEFAULTS);
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
                .isEqualTo(InstallDestination.M2);
    }
}
