// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkEnsurePendingInstallTest {

    @BeforeEach
    @AfterEach
    void forgetRegistries() {
        JdkEnsure.resetSharedRegistries();
    }

    @Test
    void a_pinned_jdk_not_on_disk_is_the_pending_install(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));

        Optional<String> pending = JdkEnsure.pendingInstall(project, jdks, "temurin-21", 0, null);

        assertThat(pending).contains("temurin-21");
    }

    @Test
    void a_pinned_jdk_on_disk_leaves_nothing_pending(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        makeJdk(jdks, "temurin-21.0.5");

        Optional<String> pending = JdkEnsure.pendingInstall(project, jdks, "temurin-21", 0, null);

        assertThat(pending).isEmpty();
    }

    @Test
    void the_probe_installs_nothing(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));

        JdkEnsure.pendingInstall(project, jdks, "temurin-21", 0, null);

        try (var entries = Files.list(jdks)) {
            assertThat(entries).isEmpty();
        }
    }

    private static void makeJdk(Path jdksRoot, String dirName) throws IOException {
        Path home = jdksRoot.resolve(dirName);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        String version = dirName.substring(dirName.indexOf('-') + 1);
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
