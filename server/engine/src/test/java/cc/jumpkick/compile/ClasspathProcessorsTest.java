// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.PlannerCompile;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The processor-discovery rule: a compile with no declared processor path runs the processors its
 * compile classpath registers; a declared path is searched alone.
 */
class ClasspathProcessorsTest {

    @Test
    void discovers_jars_and_directories_that_register_a_processor(@TempDir Path tmp) throws IOException {
        Path processor = jar(tmp.resolve("lombok.jar"), true);
        Path library = jar(tmp.resolve("guava.jar"), false);
        Path sibling = Files.createDirectories(tmp.resolve("sibling-classes"));
        Files.createDirectories(sibling.resolve("META-INF/services"));
        Files.writeString(sibling.resolve(ClasspathProcessors.SERVICE), "com.example.Gen\n");
        Path plain = Files.createDirectories(tmp.resolve("plain-classes"));

        assertThat(ClasspathProcessors.discover(List.of(library, processor, plain, sibling, tmp.resolve("absent.jar"))))
                .containsExactly(processor, sibling);
    }

    @Test
    void a_rewritten_jar_is_probed_again(@TempDir Path tmp) throws IOException {
        Path jar = jar(tmp.resolve("gen.jar"), false);
        assertThat(ClasspathProcessors.registersProcessor(jar)).isFalse();
        jar(jar, true);
        assertThat(ClasspathProcessors.registersProcessor(jar)).isTrue();
    }

    @Test
    void a_declared_processor_path_is_searched_alone(@TempDir Path tmp) throws IOException {
        Path lombok = jar(tmp.resolve("lombok.jar"), true);
        Path declared = jar(tmp.resolve("mapstruct-processor.jar"), true);

        assertThat(PlannerCompile.effectiveProcessorPath(List.of(), List.of(lombok)))
                .as("no declaration: the classpath's processors run")
                .containsExactly(lombok);
        assertThat(PlannerCompile.effectiveProcessorPath(List.of(declared), List.of(lombok)))
                .as("a declaration shadows the classpath's processors")
                .containsExactly(declared);
    }

    private static Path jar(Path file, boolean registersProcessor) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("com/example/Marker.class"));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
            if (registersProcessor) {
                jar.putNextEntry(new JarEntry(ClasspathProcessors.SERVICE));
                jar.write("com.example.Gen\n".getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }
}
