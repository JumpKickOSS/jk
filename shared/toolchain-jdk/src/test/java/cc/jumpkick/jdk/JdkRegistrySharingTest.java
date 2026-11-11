// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeJdk;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The static resolve entry points scan the host through the one registry a JDK root shares. */
class JdkRegistrySharingTest {

    @Test
    void a_pin_resolve_scans_through_the_shared_registry(@TempDir Path tmp) throws Exception {
        Path jdks = tmp.resolve("jdks");
        Path home = FakeJdk.create(jdks.resolve("temurin-25"), "25.0.1");
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve(".jdk-version"), "temurin-25");

        assertThat(JdkResolver.forProject(project, jdks))
                .map(InstalledJdk::home)
                .contains(home);

        // Gone from disk without touching the root's mtime: only a registry that kept the scan
        // above still lists it, so seeing it proves the resolve warmed the shared one.
        Files.delete(home.resolve("release"));
        assertThat(JdkEnsure.sharedRegistry(jdks, project).list())
                .extracting(InstalledJdk::home)
                .containsExactly(home);
    }
}
