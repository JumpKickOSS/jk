// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An unpinned module whose release build is not installed builds on the installed JDK that
 * satisfies it, under CI as anywhere: no failure, no download, no warning to act on.
 */
class JdkEnsureReleaseFallbackTest {

    @AfterEach
    void forget() {
        JdkEnsure.resetSharedRegistries();
    }

    @Test
    void a_developer_build_uses_what_is_installed(@TempDir Path tmp) throws Exception {
        assertUsesInstalled(project(tmp), jdks(tmp));
    }

    @Test
    void a_ci_build_uses_what_is_installed_too(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        Files.writeString(project.resolve(".env"), "CI=true\n");
        assertUsesInstalled(project, jdks(tmp));
    }

    private static void assertUsesInstalled(Path project, Path jdks) throws Exception {
        List<String> warnings = new ArrayList<>();
        JdkEnsure.Outcome outcome = JdkEnsure.ensure(project, jdks, null, 25, null, warnings::add, true);
        assertThat(outcome.jdkOpt().orElseThrow().home().getFileName().toString())
                .isEqualTo("temurin-25.0.2");
        assertThat(outcome.source()).as("nothing installed").isNotEqualTo(JdkEnsure.Source.INSTALLED);
        assertThat(warnings).isEmpty();
    }

    private static Path project(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("p"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\njava = 25\n");
        return project;
    }

    private static Path jdks(Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = jdks.resolve("temurin-25.0.2");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.2\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        return jdks;
    }
}
