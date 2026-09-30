// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkToolUninstaller;
import cc.jumpkick.jdk.JdkUninstallPolicy;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.testing.FakeJdk;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk jdk uninstall} deletes a directory only under the managed JDK root, through the real probes. */
class UninstallOutsideManagedRootTest {

    @Test
    void a_java_home_outside_the_managed_root_is_refused_and_stays(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("jdks"));
        Path home = FakeJdk.create(tmp.resolve("opt/temurin-21"), "21.0.5");
        JdkRegistry registry = new JdkRegistry(root, List.of(javaHome(home), new JkProbe(root)));

        JdkHit hit = registry.findHitBySpec("21").orElseThrow();

        assertThat(JdkUninstallPolicy.refusal(hit, root)).hasValueSatisfying(message -> assertThat(message)
                .contains("`" + hit.source() + "`")
                .contains(hit.home().toString()));
        assertThatThrownBy(() -> JdkToolUninstaller.uninstall(hit, registry))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(hit.home().toString());
        assertThat(home.resolve("release")).isRegularFile();
    }

    @Test
    void a_jdk_under_a_gradle_user_home_is_refused_and_stays(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("jdks"));
        Path gradleJdks = tmp.resolve("gradle-home/jdks");
        Path home = FakeJdk.create(gradleJdks.resolve("eclipse_temurin-21-amd64-linux.2/jdk-21.0.5+11"), "21.0.5");
        JdkRegistry registry = new JdkRegistry(root, List.of(new JkProbe(root), new GradleProbe(gradleJdks)));

        JdkHit hit = registry.findHitBySpec("21").orElseThrow();

        assertThat(hit.source()).isEqualTo("gradle");
        assertThat(JdkUninstallPolicy.refusal(hit, root)).hasValueSatisfying(message -> assertThat(message)
                .contains("`gradle`")
                .contains(hit.home().toString()));
        assertThatThrownBy(() -> JdkToolUninstaller.uninstall(hit, registry)).isInstanceOf(IOException.class);
        assertThat(home.resolve("release")).isRegularFile();
    }

    @Test
    void a_jdk_under_the_managed_root_uninstalls(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("jdks"));
        Path home = FakeJdk.create(root.resolve("temurin-25.0.3"), "25.0.3");
        JdkRegistry registry = new JdkRegistry(root, List.of(new JkProbe(root)));

        JdkHit hit = registry.findHitBySpec("25").orElseThrow();

        assertThat(JdkUninstallPolicy.refusal(hit, root)).isEmpty();
        assertThat(JdkToolUninstaller.uninstall(hit, registry)).isEqualTo(JdkToolUninstaller.Outcome.PURGED);
        assertThat(home).doesNotExist();
    }

    @Test
    void a_managed_jdk_java_home_points_at_still_uninstalls(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("jdks"));
        Path home = FakeJdk.create(root.resolve("temurin-25.0.3"), "25.0.3");
        // Only JAVA_HOME reports it, so the hit keeps the pointer's `path` label.
        JdkRegistry registry = new JdkRegistry(root, List.of(javaHome(home)));

        JdkHit hit = registry.findHitBySpec("25").orElseThrow();

        assertThat(hit.source()).isEqualTo("path");
        assertThat(JdkUninstallPolicy.refusal(hit, root)).isEmpty();
        assertThat(JdkToolUninstaller.uninstall(hit, registry)).isEqualTo(JdkToolUninstaller.Outcome.PURGED);
        assertThat(home).doesNotExist();
    }

    @Test
    void a_symlink_in_the_managed_root_to_a_jdk_outside_it_is_refused(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("jdks"));
        Path outside = FakeJdk.create(tmp.resolve("opt/temurin-21"), "21.0.5");
        Path link = Symlinks.create(root.resolve("temurin-21.0.5"), outside);
        JdkHit hit = new JdkHit(link, "21.0.5", JdkVendor.TEMURIN, "jk");
        JdkRegistry registry = new JdkRegistry(root, List.of(new JkProbe(root)));

        assertThat(JdkUninstallPolicy.deletable(link, root)).isFalse();
        assertThat(JdkUninstallPolicy.refusal(hit, root)).isPresent();
        assertThatThrownBy(() -> registry.purge(hit)).isInstanceOf(IOException.class);
        assertThat(outside.resolve("release")).isRegularFile();
    }

    @Test
    void a_managed_root_reached_through_a_symlink_still_uninstalls(@TempDir Path tmp) throws IOException {
        Path realRoot = Files.createDirectories(tmp.resolve("real-jdks"));
        Path root = Symlinks.create(tmp.resolve("jdks"), realRoot);
        Path home = FakeJdk.create(realRoot.resolve("temurin-25.0.3"), "25.0.3");
        JdkRegistry registry = new JdkRegistry(root, List.of(new JkProbe(root)));

        JdkHit hit = registry.findHitBySpec("25").orElseThrow();

        assertThat(JdkToolUninstaller.uninstall(hit, registry)).isEqualTo(JdkToolUninstaller.Outcome.PURGED);
        assertThat(home).doesNotExist();
    }

    private static EnvVarProbe javaHome(Path home) {
        return new EnvVarProbe(Map.of("JAVA_HOME", home.toString())::get);
    }
}
