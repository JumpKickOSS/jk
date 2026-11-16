// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When this release's JDK build for an unpinned module cannot be installed (here: a resolution
 * that may not install, as offline or in an engine that does not), a developer's build goes on with
 * an installed JDK of the major and says, by name, that this host then shares no key and what to run;
 * under {@code CI} the build fails with the same words instead.
 */
class JdkEnsureReleaseFallbackTest {

    private static final String RELEASE = ReleaseJdks.spec(25).orElseThrow();

    @AfterEach
    void forget() {
        JdkEnsure.resetSharedRegistries();
    }

    @Test
    void a_developer_build_falls_back_loudly(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        Path jdks = jdks(tmp);
        List<String> warnings = new ArrayList<>();

        JdkEnsure.Outcome outcome = JdkEnsure.ensure(project, jdks, null, 25, null, warnings::add, false);

        assertThat(outcome.jdkOpt().orElseThrow().home().getFileName().toString())
                .isEqualTo("temurin-25.0.2");
        assertThat(warnings)
                .singleElement()
                .asString()
                .contains("this jk builds unpinned modules on " + RELEASE, "run jk jdk install " + RELEASE)
                .contains("shares no compile or test key with hosts on " + RELEASE);
    }

    @Test
    void a_ci_build_fails_naming_the_build_and_the_command(@TempDir Path tmp) throws Exception {
        Path project = project(tmp);
        Files.writeString(project.resolve(".env"), "CI=true\n");
        Path jdks = jdks(tmp);

        assertThatThrownBy(() -> JdkEnsure.ensure(project, jdks, null, 25, null, m -> {}, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("run jk jdk install " + RELEASE)
                .hasMessageContaining("CI builds only on it");
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
