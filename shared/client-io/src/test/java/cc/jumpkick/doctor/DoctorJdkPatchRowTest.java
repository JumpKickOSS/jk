// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.HostRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk doctor}'s {@code jdk-patch} row says, as a note, which JDK major a project builds on and
 * which cached results serve it under the cache's {@code jdk-match}; when no JDK of the project's
 * level is installed it names the two ways out (JK-74S9K).
 */
class DoctorJdkPatchRowTest {

    @Test
    void a_level_with_its_jdk_installed_is_a_plain_note(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 25\n");
        HostRow row = DoctorChecks.jdkPatch(project, List.of(21, 25, 27), null, null);
        assertThat(row.status()).isEqualTo(HostRow.Status.OK);
        assertThat(row.detail())
                .isEqualTo(
                        "Java 25: builds on an installed JDK 25 and takes cached results made on JDK 25 (jdk-match = major)");
    }

    @Test
    void no_jdk_of_the_level_names_the_two_ways_out(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 21\n");
        HostRow row = DoctorChecks.jdkPatch(project, List.of(25, 27), null, null);
        assertThat(row.status()).as("a note, not a fault").isEqualTo(HostRow.Status.OK);
        assertThat(row.detail())
                .contains("no JDK 21 installed, so it builds on JDK 25")
                .contains("jk jdk install temurin-21", "jdk-match = \"level\"");
    }

    @Test
    void the_level_match_takes_any_newer_jdk(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 21\n\n[cache]\njdk-match = \"level\"\n");
        assertThat(DoctorChecks.jdkPatch(project, List.of(25), null, null).detail())
                .contains("any JDK 21 or newer", "jdk-match = level");
    }

    @Test
    void a_binding_requirement_is_named(@TempDir Path project) throws Exception {
        Files.writeString(
                project.resolve("jk.toml"), "name = \"app\"\njdk = \"=corretto-21\"\njdk-cache = \"required\"\n");
        assertThat(DoctorChecks.jdkPatch(project, List.of(21), null, null).detail())
                .startsWith("requires corretto-21 (jdk-cache = required)");
    }
}
