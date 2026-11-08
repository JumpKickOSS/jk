// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JavaHomes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which JDK compiles a module ({@link CompilerJdk}): jk's runtime for a level it can emit, the
 * project's JDK for a newer level, and the project's JDK whenever the author pinned it; the worker
 * runs on the home the engine decided.
 */
class ForkedJavacWorkerHomeTest {

    private static final int HOST = 25;

    private static Path jdk(Path dir, int feature) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"" + feature + ".0.1\"\n");
        return dir;
    }

    @Test
    void a_language_level_the_runtime_can_emit_compiles_on_the_runtime(@TempDir Path dir) throws Exception {
        Path host = jdk(dir.resolve("host"), HOST);
        Path jdk21 = jdk(dir.resolve("jdk-21"), 21);
        Path jdk27 = jdk(dir.resolve("jdk-27"), 27);
        assertThat(CompilerJdk.home(jdk21, 21, false, host)).as("java = 21").isEqualTo(host);
        assertThat(CompilerJdk.home(jdk27, HOST, false, host))
                .as("java = 25 on a JDK 27")
                .isEqualTo(host);
        assertThat(CompilerJdk.home(null, 27, false, host))
                .as("no project JDK to move to")
                .isEqualTo(host);
    }

    @Test
    void a_level_above_the_runtime_compiles_on_the_project_jdk_that_can_emit_it(@TempDir Path dir) throws Exception {
        Path host = jdk(dir.resolve("host"), HOST);
        Path jdk27 = jdk(dir.resolve("jdk-27"), 27);
        Path jdk24 = jdk(dir.resolve("jdk-24"), 24);
        assertThat(CompilerJdk.home(jdk27, 27, false, host)).isEqualTo(jdk27);
        assertThat(CompilerJdk.home(jdk24, 27, false, host))
                .as("a project JDK that cannot emit the level either is no better than the runtime")
                .isEqualTo(host);
    }

    @Test
    void a_pinned_jdk_compiles_on_that_jdk_even_below_the_runtime(@TempDir Path dir) throws Exception {
        Path host = jdk(dir.resolve("host"), HOST);
        Path jdk21 = jdk(dir.resolve("jdk-21"), 21);
        Path jdk17 = jdk(dir.resolve("jdk-17"), 17);
        assertThat(CompilerJdk.home(jdk21, 21, true, host)).as("jdk = 21").isEqualTo(jdk21);
        assertThat(CompilerJdk.home(jdk21, 17, true, host))
                .as("jdk = 21, java = 17")
                .isEqualTo(jdk21);
        assertThat(CompilerJdk.home(jdk17, 17, true, host))
                .as("below the worker's floor the runtime compiles it")
                .isEqualTo(host);
        assertThat(CompilerJdk.home(jdk21, 25, true, host))
                .as("a pinned JDK too old for the level cannot compile it")
                .isEqualTo(host);
    }

    @Test
    void the_worker_runs_on_the_home_the_engine_decided(@TempDir Path dir) throws Exception {
        Path jdk21 = jdk(dir.resolve("jdk-21"), 21);
        assertThat(ForkedJavac.workerJavaHome(request(dir, jdk21))).isEqualTo(jdk21);
        assertThat(ForkedJavac.workerJavaHome(request(dir, null))).isEqualTo(JavaHomes.runningJavaHome());
    }

    private static ForkedJavac.Request request(Path dir, @Nullable Path javaHome) {
        return new ForkedJavac.Request(
                javaHome,
                dir.resolve("worker.jar"),
                List.of(dir.resolve("C.java")),
                List.of(),
                List.of(),
                dir.resolve("classes"),
                dir.resolve("gen"),
                21,
                List.of());
    }
}
