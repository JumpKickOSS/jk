// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.SessionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared JDK registry answers each request from that request's environment: a listing made
 * under one shell never answers the next request whose shell names another {@code JAVA_HOME}.
 */
class JdkEnsureRequestEnvTest {

    @BeforeEach
    @AfterEach
    void reset() {
        JdkEnsure.resetSharedRegistries();
        SessionContext.reset();
    }

    @Test
    void a_listing_under_one_request_s_environment_does_not_answer_the_next_request(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path javaHome = fakeJdk(tmp.resolve("elsewhere/temurin-21.0.5"));
        SessionContext.install(SessionContext.current().withVariant(null, Map.of("JK_JDKS_DIR", root.toString())));

        List<Path> withoutJavaHome = homes(Map.of("PATH", tmp.toString()), project);
        List<Path> withJavaHome = homes(Map.of("PATH", tmp.toString(), "JAVA_HOME", javaHome.toString()), project);
        List<Path> withoutAgain = homes(Map.of("PATH", tmp.toString()), project);

        assertThat(withoutJavaHome).doesNotContain(javaHome);
        assertThat(withJavaHome).as("the request naming JAVA_HOME lists it").contains(javaHome);
        assertThat(withoutAgain).as("and the next request without it does not").doesNotContain(javaHome);
    }

    private static List<Path> homes(Map<String, String> shell, Path project) throws Exception {
        return RequestShell.where(shell, () -> JdkEnsure.sharedRegistry(null, project).listHits().stream()
                .map(JdkHit::home)
                .toList());
    }

    private static Path fakeJdk(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.5\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        return home;
    }
}
