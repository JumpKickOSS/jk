// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.HostRow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk doctor} says when a project compiles and tests on whatever JDK build this host has,
 * which a host on another patch shares no key with, and names the exact pin that makes them share.
 */
class DoctorJdkPatchRowTest {

    @Test
    void an_unpinned_project_is_told_which_pin_makes_hosts_share_keys(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 25\n");
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Files.createDirectories(jdks.resolve("temurin-25.0.4.1"));
        Files.createDirectories(jdks.resolve("temurin-25.0.10"));
        Files.createDirectories(jdks.resolve("temurin-21.0.12.1"));

        HostRow row = DoctorChecks.jdkPatch(project, jdks);
        assertThat(row.id()).isEqualTo("jdk-patch");
        assertThat(row.status()).as("not a fault").isEqualTo(HostRow.Status.OK);
        assertThat(row.detail()).contains("not pinned to a JDK build", "shares no compile or test key");
        assertThat(row.fix()).isEqualTo("pin one in jk.toml for a shared cache: jdk = \"=temurin-25.0.10\"");
    }

    @Test
    void a_pin_short_of_the_patch_is_still_unpinned(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njdk = \"=temurin-25\"\n");
        assertThat(DoctorChecks.jdkPatch(project, project.resolve("none")).detail())
                .contains("not pinned to a JDK build");
    }

    @Test
    void an_exact_pin_is_named(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njdk = \"=temurin-25.0.4.1\"\n");
        HostRow row = DoctorChecks.jdkPatch(project, project.resolve("none"));
        assertThat(row.detail())
                .isEqualTo("pinned to temurin-25.0.4.1: every host compiles and tests on that JDK build");
        assertThat(row.fix()).isNull();
    }
}
