// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One compile set for groovyc: what {@code ActionKey.forGroovyc} hashes is what rides the spec's
 * {@code SOURCE} lines, so the worker compiles exactly the hashed set and never walks a tree.
 */
class GroovycInputsTest {

    @Test
    void the_set_is_sources_plus_each_roots_java_deduped(@TempDir Path dir) throws Exception {
        Path groovy = Files.createDirectories(dir.resolve("src/groovy")).resolve("A.groovy");
        Files.writeString(groovy, "class A {}");
        Path javaRoot = Files.createDirectories(dir.resolve("src/java"));
        Path helper = Files.createDirectories(javaRoot.resolve("dep")).resolve("Helper.java");
        Files.writeString(helper, "package dep; class Helper {}");
        // Also declared explicitly: the union must not carry it twice.
        Path shared = javaRoot.resolve("Shared.java");
        Files.writeString(shared, "class Shared {}");

        GroovycRequest request = GroovycRequest.builder()
                .sources(List.of(groovy, shared))
                .javaSourceRoots(List.of(javaRoot))
                .outputDir(dir.resolve("out"))
                .jvmTarget(25)
                .workerClasspath(List.of(dir.resolve("worker.jar")))
                .build();

        List<Path> set = GroovycInputs.compileSet(request);

        assertThat(set)
                .containsExactly(
                        groovy.toAbsolutePath().normalize(),
                        shared.toAbsolutePath().normalize(),
                        helper.toAbsolutePath().normalize());
    }

    @Test
    void a_missing_root_contributes_nothing(@TempDir Path dir) throws Exception {
        Path groovy = dir.resolve("A.groovy");
        Files.writeString(groovy, "class A {}");
        GroovycRequest request = GroovycRequest.builder()
                .sources(List.of(groovy))
                .javaSourceRoots(List.of(dir.resolve("absent")))
                .outputDir(dir.resolve("out"))
                .jvmTarget(25)
                .workerClasspath(List.of(dir.resolve("worker.jar")))
                .build();

        assertThat(GroovycInputs.compileSet(request))
                .containsExactly(groovy.toAbsolutePath().normalize());
    }
}
