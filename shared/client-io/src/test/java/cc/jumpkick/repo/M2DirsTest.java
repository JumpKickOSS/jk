// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M2DirsTest {

    @AfterEach
    void clear() {
        System.clearProperty("jk.m2.local");
        System.clearProperty("maven.repo.local");
        System.clearProperty("jk.m2.settings");
    }

    @Test
    void jk_m2_local_wins(@TempDir Path dir) {
        Path custom = dir.resolve("custom-m2");
        System.setProperty("jk.m2.local", custom.toString());
        assertThat(M2Dirs.localRepository()).isEqualTo(custom);
    }

    @Test
    void maven_repo_local_is_used_when_jk_override_absent(@TempDir Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JK_M2_LOCAL") == null
                || System.getenv("JK_M2_LOCAL").isBlank());
        Path maven = dir.resolve("maven-local");
        System.setProperty("maven.repo.local", maven.toString());
        assertThat(M2Dirs.localRepository()).isEqualTo(maven);
    }

    @Test
    void settings_xml_local_repository_is_used_when_no_override(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JK_M2_LOCAL") == null
                || System.getenv("JK_M2_LOCAL").isBlank());
        Path custom = dir.resolve("from-settings");
        Path settings = dir.resolve("settings.xml");
        Files.writeString(settings, "<settings><localRepository>" + custom + "</localRepository></settings>");
        System.setProperty("jk.m2.settings", settings.toString());
        System.clearProperty("jk.m2.local");
        System.clearProperty("maven.repo.local");
        assertThat(M2Dirs.localRepository()).isEqualTo(custom);
    }

    @Test
    void interpolated_settings_xml_path_is_skipped(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JK_M2_LOCAL") == null
                || System.getenv("JK_M2_LOCAL").isBlank());
        Path settings = dir.resolve("settings.xml");
        Files.writeString(
                settings, "<settings><localRepository>${user.home}/.m2/repository</localRepository></settings>");
        System.setProperty("jk.m2.settings", settings.toString());
        System.clearProperty("jk.m2.local");
        System.clearProperty("maven.repo.local");
        assertThat(M2Dirs.localRepository()).isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }
}
