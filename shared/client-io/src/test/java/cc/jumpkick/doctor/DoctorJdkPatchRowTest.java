// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.HostRow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk doctor}'s {@code jdk-patch} row says which JDK build a project builds on here and that
 * hosts on another build share no JDK-dependent key with it, always as a note, never a fault: any
 * JDK that satisfies the module's {@code java} level builds it (JK-ADBJV).
 */
class DoctorJdkPatchRowTest {

    private static final String RELEASE = "temurin-25.0.4.1";

    @Test
    void an_unpinned_project_on_the_installed_preferred_build_is_a_note(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 25\n");
        HostRow row = DoctorChecks.jdkPatch(project, RELEASE, true, null);
        assertThat(row.id()).isEqualTo("jdk-patch");
        assertThat(row.status()).isEqualTo(HostRow.Status.OK);
        assertThat(row.detail()).startsWith("unpinned: builds on temurin-25.0.4.1, this jk's preferred build");
        assertThat(row.fix()).isNull();
    }

    @Test
    void the_preferred_build_not_installed_is_a_note_with_nothing_to_fix(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        HostRow row = DoctorChecks.jdkPatch(project, RELEASE, false, null);
        assertThat(row.status()).as("not a fault").isEqualTo(HostRow.Status.OK);
        assertThat(row.detail()).contains("not temurin-25.0.4.1", "not installed here", "share no compile or test key");
        assertThat(row.fix()).isNull();
    }

    @Test
    void a_pin_and_jk_jdk_are_named_as_notes(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njdk = \"=temurin-25.0.3\"\n");
        HostRow pinned = DoctorChecks.jdkPatch(project, RELEASE, true, null);
        assertThat(pinned.status()).isEqualTo(HostRow.Status.OK);
        assertThat(pinned.detail()).startsWith("pinned to temurin-25.0.3");

        HostRow shell = DoctorChecks.jdkPatch(project, RELEASE, true, "corretto-25");
        assertThat(shell.status()).isEqualTo(HostRow.Status.OK);
        assertThat(shell.detail()).startsWith("JK_JDK=corretto-25");
        assertThat(shell.fix()).isNull();
    }
}
