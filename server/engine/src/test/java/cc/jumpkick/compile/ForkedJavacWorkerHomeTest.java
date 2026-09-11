// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JavaHomes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compiler worker runs on jk's runtime unless the project's level is above what its javac emits. */
class ForkedJavacWorkerHomeTest {

    private static Path jdk(Path dir, int feature) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"" + feature + ".0.1\"\n");
        return dir;
    }

    private static ForkedJavac.Request request(Path dir, Path javaHome, int release) {
        return new ForkedJavac.Request(
                javaHome,
                dir.resolve("worker.jar"),
                List.of(dir.resolve("C.java")),
                List.of(),
                List.of(),
                dir.resolve("classes"),
                dir.resolve("gen"),
                release,
                List.of());
    }

    @Test
    void a_level_the_runtime_s_javac_can_emit_stays_on_the_runtime(@TempDir Path dir) throws Exception {
        int runtime = Runtime.version().feature();
        Path newer = jdk(dir.resolve("jdk-newer"), runtime + 1);
        assertThat(ForkedJavac.workerJavaHome(request(dir, newer, runtime))).isEqualTo(JavaHomes.runningJavaHome());
        assertThat(ForkedJavac.workerJavaHome(request(dir, null, runtime + 1)))
                .as("no project JDK to move to")
                .isEqualTo(JavaHomes.runningJavaHome());
    }

    @Test
    void a_level_above_the_runtime_runs_on_the_project_s_jdk_when_that_jdk_can_emit_it(@TempDir Path dir)
            throws Exception {
        int runtime = Runtime.version().feature();
        Path newer = jdk(dir.resolve("jdk-newer"), runtime + 1);
        Path older = jdk(dir.resolve("jdk-older"), runtime - 1);
        assertThat(ForkedJavac.workerJavaHome(request(dir, newer, runtime + 1))).isEqualTo(newer);
        assertThat(ForkedJavac.workerJavaHome(request(dir, older, runtime + 1)))
                .as("a project JDK that cannot emit the level either is no better than the runtime")
                .isEqualTo(JavaHomes.runningJavaHome());
    }
}
