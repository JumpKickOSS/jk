// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which root the resolve-or-install registry writes to when no {@code --jdks-dir} was given: the
 * request's {@code JK_JDKS_DIR} when the caller's shell set one, else this process's.
 */
class JdkEnsureRequestRootTest {

    @BeforeEach
    @AfterEach
    void reset() {
        JdkEnsure.resetSharedRegistries();
        SessionContext.reset();
    }

    @Test
    void the_request_s_jdk_root_wins_over_the_daemon_s(@TempDir Path tmp) throws IOException {
        Path callers = Files.createDirectories(tmp.resolve("callers-jdks"));
        SessionContext.install(SessionContext.current().withVariant(null, Map.of("JK_JDKS_DIR", callers.toString())));

        JdkRegistry registry = JdkEnsure.sharedRegistry(null, null);

        assertThat(registry.jdksRoot()).isEqualTo(callers.toAbsolutePath().normalize());
    }

    @Test
    void an_explicit_override_still_walks_its_one_directory_alone(@TempDir Path tmp) throws IOException {
        Path callers = Files.createDirectories(tmp.resolve("callers-jdks"));
        Path override = Files.createDirectories(tmp.resolve("override"));
        SessionContext.install(SessionContext.current().withVariant(null, Map.of("JK_JDKS_DIR", callers.toString())));

        JdkRegistry registry = JdkEnsure.sharedRegistry(override, null);

        assertThat(registry.jdksRoot()).isEqualTo(override.toAbsolutePath().normalize());
    }

    @Test
    void a_jdk_in_the_request_s_root_is_found_without_an_override(@TempDir Path tmp) throws IOException {
        Path callers = Files.createDirectories(tmp.resolve("callers-jdks"));
        Path home = callers.resolve("temurin-21.0.5");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.5\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        SessionContext.install(SessionContext.current().withVariant(null, Map.of("JK_JDKS_DIR", callers.toString())));

        assertThat(JdkEnsure.sharedRegistry(null, null).findBySpec("temurin-21"))
                .isPresent()
                .get()
                .extracting(InstalledJdk::home)
                .isEqualTo(home);
    }

    @Test
    void a_build_root_gradle_properties_is_read_only_when_it_exists(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("app"));
        assertThat(JdkEnsure.gradlePropertiesRoot(project)).isNull();
        assertThat(JdkEnsure.gradlePropertiesRoot(null)).isNull();

        Files.writeString(project.resolve("gradle.properties"), "org.gradle.java.installations.paths=/x\n");

        assertThat(JdkEnsure.gradlePropertiesRoot(project))
                .isEqualTo(project.toAbsolutePath().normalize());
    }
}
