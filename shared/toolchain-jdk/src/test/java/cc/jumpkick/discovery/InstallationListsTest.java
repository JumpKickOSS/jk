// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkUninstallPolicy;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationListsTest {

    @Test
    void user_home_paths_and_from_env_contribute_and_a_missing_path_does_not(@TempDir Path tmp) throws IOException {
        Path listed = FakeJdk.create(tmp.resolve("jdks/temurin-21"), "21.0.5");
        Path viaEnv = FakeJdk.create(tmp.resolve("jdks/zulu-25"), "25.0.1");
        Path parent = tmp.resolve("jdks"); // named, but not descended into
        writeProperties(
                tmp.resolve("user/.gradle"),
                "org.gradle.java.installations.paths=" + listed + ", " + tmp.resolve("gone") + "," + parent + "\n"
                        + "org.gradle.java.installations.fromEnv=ZULU_HOME,UNSET_HOME\n"
                        + "org.gradle.java.installations.auto-detect=false\n");
        Map<String, String> env = Map.of("ZULU_HOME", viaEnv.toString());

        List<JdkHit> hits =
                new GradlePropertiesProbe(env::get, tmp.resolve("user").toString(), null).discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(listed.toRealPath(), viaEnv.toRealPath());
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("gradle-properties"));
    }

    @Test
    void gradle_user_home_replaces_the_default(@TempDir Path tmp) throws IOException {
        Path listed = FakeJdk.create(tmp.resolve("jdks/temurin-21"), "21.0.5");
        writeProperties(tmp.resolve("gh"), "org.gradle.java.installations.paths=" + listed + "\n");
        Map<String, String> env = Map.of("GRADLE_USER_HOME", tmp.resolve("gh").toString());

        assertThat(new GradlePropertiesProbe(env::get, tmp.resolve("user").toString(), null).discoverAllJdks())
                .extracting(JdkHit::home)
                .containsExactly(listed.toRealPath());
    }

    @Test
    void the_build_root_file_is_read_only_when_a_build_names_its_root(@TempDir Path tmp) throws IOException {
        Path listed = FakeJdk.create(tmp.resolve("jdks/temurin-21"), "21.0.5");
        Path build = writeProperties(tmp.resolve("build"), "org.gradle.java.installations.paths=" + listed + "\n");
        String userHome = tmp.resolve("user").toString();

        assertThat(new GradlePropertiesProbe(name -> null, userHome, null).discoverAllJdks())
                .isEmpty();
        assertThat(new GradlePropertiesProbe(name -> null, userHome, build).discoverAllJdks())
                .extracting(JdkHit::home)
                .containsExactly(listed.toRealPath());
    }

    @Test
    void a_named_maven_toolchains_file_is_parsed(@TempDir Path tmp) throws IOException {
        Path home = FakeJdk.create(tmp.resolve("jdks/temurin-21"), "21.0.5");
        Path toolchains = MavenToolchainsProbeTest.toolchains(
                Files.createDirectories(tmp.resolve("elsewhere")),
                MavenToolchainsProbeTest.toolchain("jdk", home.toString()));
        writeProperties(
                tmp.resolve("user/.gradle"),
                "org.gradle.java.installations.maven-toolchains-file=" + toolchains + "\n");

        assertThat(new GradlePropertiesProbe(name -> null, tmp.resolve("user").toString(), null).discoverAllJdks())
                .extracting(JdkHit::home)
                .containsExactly(home.toRealPath());
    }

    @Test
    void jk_jdk_paths_and_from_env_work_without_a_gradle_file(@TempDir Path tmp) throws IOException {
        Path listed = FakeJdk.create(tmp.resolve("jdks/temurin-21"), "21.0.5");
        Path viaEnv = FakeJdk.create(tmp.resolve("jdks/zulu-25"), "25.0.1");
        Map<String, String> env = Map.of(
                "JK_JDK_PATHS",
                listed + "," + tmp.resolve("gone"),
                "JK_JDK_FROM_ENV",
                "ZULU_HOME",
                "ZULU_HOME",
                viaEnv.toString());

        List<JdkHit> hits = new JdkPathsProbe(env::get).discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(listed.toRealPath(), viaEnv.toRealPath());
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("jdk-paths"));
    }

    @Test
    void list_hits_are_refused_by_uninstall() {
        assertThat(JdkUninstallPolicy.removable("gradle-properties")).isFalse();
        assertThat(JdkUninstallPolicy.removable("jdk-paths")).isFalse();
    }

    private static Path writeProperties(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("gradle.properties"), content);
        return dir;
    }
}
