// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One driver, two languages — and the difference between them must survive the consolidation.
 *
 * <p>kotlinc cross-compiles against the project's pinned JDK and is handed it as {@code -jdk-home}.
 * groovyc takes no project JDK at all: {@link GroovycRequest} has no {@code javaHome} to give it,
 * and its spec must not grow one. If a future refactor "unifies" the spec writers, this goes red.
 */
class WorkerCompileDriverTest {

    @Test
    void kotlinc_receives_jdk_home_and_groovyc_does_not(@TempDir Path dir) throws IOException {
        Path javaHome = Files.createDirectories(dir.resolve("temurin-21"));

        List<String> kotlinArgs = argsOf(KotlincSpec.write(KotlincRequest.builder()
                .sources(List.of(source(dir, "Main.kt", "fun main() {}")))
                .outputDir(dir.resolve("classes"))
                .jvmTarget(21)
                .workerClasspath(List.of(source(dir, "worker.jar", "worker")))
                .javaHome(javaHome)
                .build()));
        List<String> groovyArgs = argsOf(GroovycSpec.write(GroovycRequest.builder()
                .sources(List.of(source(dir, "Main.groovy", "println 'hi'")))
                .outputDir(dir.resolve("classes"))
                .jvmTarget(21)
                .workerClasspath(List.of(source(dir, "worker.jar", "worker")))
                .build()));

        assertThat(kotlinArgs)
                .containsSequence(
                        "-jdk-home", javaHome.toAbsolutePath().normalize().toString());
        assertThat(groovyArgs).doesNotContain("-jdk-home");
        // Not "no args at all" — groovyc's spec is populated, it simply has no project JDK in it.
        assertThat(groovyArgs).noneMatch(a -> a.contains(javaHome.getFileName().toString()));
    }

    /**
     * Groovy's joint-compilation pass is the other half of the asymmetry: stubs are a groovyc
     * input with no Kotlin counterpart, and the roots' Java neighborhood rides the SOURCE lines
     * themselves ({@code GroovycInputs}) — the worker compiles the listed set verbatim and never
     * walks a tree, so the set the action key hashed is the set that compiles.
     */
    @Test
    void groovyc_carries_the_joint_compilation_inputs_kotlin_has_no_field_for(@TempDir Path dir) throws IOException {
        Path stubs = dir.resolve("stubs");
        Path javaRoot = Files.createDirectories(dir.resolve("src/main/java"));
        Path neighbor = source(javaRoot, "Dep.java", "class Dep {}");
        Path groovy = source(dir, "Main.groovy", "println 'hi'");
        Path spec = GroovycSpec.write(GroovycRequest.builder()
                .sources(List.of(groovy))
                .javaSourceRoots(List.of(javaRoot))
                .outputDir(dir.resolve("classes"))
                .stubsOut(stubs)
                .jvmTarget(21)
                .workerClasspath(List.of(source(dir, "worker.jar", "worker")))
                .build());
        try {
            PluginSpec read = PluginSpec.read(spec);
            assertThat(read.extra("stubsOut")).contains(stubs.toAbsolutePath().normalize());
            assertThat(read.sources())
                    .containsExactly(
                            groovy.toAbsolutePath().normalize(),
                            neighbor.toAbsolutePath().normalize());
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static List<String> argsOf(Path spec) throws IOException {
        try {
            return PluginSpec.read(spec).args();
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static Path source(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) Files.writeString(file, body);
        return file;
    }
}
