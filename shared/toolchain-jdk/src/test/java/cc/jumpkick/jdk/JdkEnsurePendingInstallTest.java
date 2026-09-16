// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.JdkPin;
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

        Optional<JdkEnsure.Pending> pending = JdkEnsure.pendingInstall(project, jdks, "temurin-21", 0, null);

        assertThat(pending).contains(new JdkEnsure.Pending("temurin-21", JdkResolution.Tier.PROJECT_TOML));
    }

    @Test
    void a_pinned_jdk_on_disk_leaves_nothing_pending(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        makeJdk(jdks, "temurin-21.0.5");

        Optional<JdkEnsure.Pending> pending = JdkEnsure.pendingInstall(project, jdks, "temurin-21", 0, null);

        assertThat(pending).isEmpty();
    }

    @Test
    void a_jdk_version_file_is_the_tier_that_asks(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve(".jdk-version"), "zulu-21\n");

        Optional<JdkEnsure.Pending> pending = JdkEnsure.pendingInstall(project, jdks, "temurin-25", 0, null);

        assertThat(pending).contains(new JdkEnsure.Pending("zulu-21", JdkResolution.Tier.JDK_VERSION_FILE));
    }

    @Test
    void a_required_lock_pin_is_the_tier_that_asks(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        JdkPin lock = new JdkPin("temurin", "", "", "21.0.5");

        Optional<JdkEnsure.Pending> pending = JdkEnsure.pendingInstall(project, jdks, null, 0, lock);

        assertThat(pending).isPresent();
        assertThat(pending.get().tier()).isEqualTo(JdkResolution.Tier.LOCKFILE);
    }

    @Test
    void a_java_level_above_the_latest_lts_is_the_tier_that_asks(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        int above = JdkLts.OFFLINE_LATEST_LTS + 1;

        Optional<JdkEnsure.Pending> pending = JdkEnsure.pendingInstall(project, jdks, null, above, null);

        assertThat(pending).contains(new JdkEnsure.Pending(">=" + above, JdkResolution.Tier.JAVA_RELEASE_FLOOR));
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
