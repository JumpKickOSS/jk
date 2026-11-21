// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.InstallDestination;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * The documented ladder is the whole ladder: no JVM property names the destination or the
     * {@code [m2]} mode it follows, and the {@code jk.env.*} seam stands for the environment.
     */
    @Test
    void no_jvm_property_names_the_destination(@TempDir Path tmp) {
        System.setProperty("jk.env.JK_HOME", tmp.resolve("home").toString());
        try {
            System.setProperty("jk.install.destination", "m2");
            System.setProperty("jk.m2.integration", "share");
            assertThat(JkInstallConfig.resolve().destination()).isEqualTo(InstallDestination.JK);

            System.setProperty("jk.env.JK_M2_INTEGRATION", "share");
            assertThat(JkInstallConfig.resolve().destination()).isEqualTo(InstallDestination.M2);
        } finally {
            for (String name : List.of(
                    "jk.env.JK_HOME", "jk.install.destination", "jk.m2.integration", "jk.env.JK_M2_INTEGRATION")) {
                System.clearProperty(name);
            }
        }
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
