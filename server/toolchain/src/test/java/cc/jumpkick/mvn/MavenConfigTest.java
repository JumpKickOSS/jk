// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code .mvn/maven.config}: where Maven looks for it, and which of its options are properties. */
class MavenConfigTest {

    @Test
    void every_spelling_of_a_define_is_a_property_and_other_options_are_skipped() {
        Properties props = MavenConfig.parse(List.of(
                "# CI-friendly version",
                "-Drevision=1.2.3 -Dchangelist=",
                "",
                "--batch-mode",
                "-T 1C",
                "-D sha1=abc",
                "--define flag.one=on",
                "-D",
                "split=yes",
                "-DskipTests"));

        assertThat(props)
                .containsEntry("revision", "1.2.3")
                .containsEntry("changelist", "")
                .containsEntry("sha1", "abc")
                .containsEntry("flag.one", "on")
                .containsEntry("split", "yes")
                .containsEntry("skipTests", "true")
                .hasSize(6);
    }

    @Test
    void the_config_is_the_one_of_the_nearest_directory_with_a_mvn_directory(@TempDir Path root) throws Exception {
        Path module = Files.createDirectories(root.resolve("a/b"));
        Path pom = Files.writeString(module.resolve("pom.xml"), "<project/>");

        assertThat(MavenConfig.file(pom)).as("no .mvn anywhere").isEqualTo(module.resolve(".mvn/maven.config"));
        assertThat(MavenConfig.properties(pom)).isEmpty();

        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/maven.config"), "-Drevision=7\n");

        assertThat(MavenConfig.file(pom)).isEqualTo(root.resolve(".mvn/maven.config"));
        assertThat(MavenConfig.properties(pom)).containsEntry("revision", "7");
    }
}
