// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.HostRow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk doctor}'s {@code jdk-patch} row is quiet for an unpinned project, which every host on
 * one jk release builds on the release's JDK build, and warns only where this host diverges: the
 * build is not installed, {@code JK_JDK} forces another, or the project pins an older build.
 */
class DoctorJdkPatchRowTest {

    private static final String RELEASE = "temurin-25.0.4.1";

    @Test
    void an_unpinned_project_on_the_installed_release_build_is_ok(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 25\n");
        HostRow row = DoctorChecks.jdkPatch(project, RELEASE, true, null);
        assertThat(row.id()).isEqualTo("jdk-patch");
        assertThat(row.status()).isEqualTo(HostRow.Status.OK);
        assertThat(row.detail())
                .isEqualTo("unpinned: compiles and tests on temurin-25.0.4.1, as every host on this jk release does");
        assertThat(row.fix()).isNull();
    }

    @Test
    void a_release_build_not_installed_names_the_install(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        HostRow row = DoctorChecks.jdkPatch(project, RELEASE, false, null);
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).contains("is not installed", "shares no compile or test key");
        assertThat(row.fix()).isEqualTo("run jk jdk install temurin-25.0.4.1");
    }

    @Test
    void a_pin_older_than_the_release_build_warns_and_one_at_it_does_not(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njdk = \"=temurin-25.0.3\"\n");
        HostRow older = DoctorChecks.jdkPatch(project, RELEASE, true, null);
        assertThat(older.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(older.detail()).startsWith("pinned to temurin-25.0.3, older than temurin-25.0.4.1");

        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njdk = \"=temurin-25.0.4.1\"\n");
        HostRow at = DoctorChecks.jdkPatch(project, RELEASE, true, null);
        assertThat(at.status()).isEqualTo(HostRow.Status.OK);
        assertThat(at.detail()).isEqualTo("pinned to temurin-25.0.4.1: every host compiles and tests on that build");
    }

    @Test
    void jk_jdk_in_the_shell_warns(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        HostRow row = DoctorChecks.jdkPatch(project, RELEASE, true, "corretto-25");
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.fix()).isEqualTo("unset JK_JDK");
    }
}
