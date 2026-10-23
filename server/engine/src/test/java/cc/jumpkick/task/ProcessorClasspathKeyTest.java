// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a compile that runs annotation processors reads of its classpath beyond the API: a
 * dependency's {@code META-INF} resources, and, when javac finds the processors on the classpath
 * itself, their bodies.
 */
class ProcessorClasspathKeyTest {

    private static Path jar(Path file, byte[] cls, String service) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("C.class"));
            out.write(cls);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/services/javax.annotation.processing.Processor"));
            out.write(service.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static List<String> tokens(Path dir, Path cp, List<Path> processorPath, List<String> options)
            throws Exception {
        CompileRequest request = CompileRequest.builder()
                .outputDir(dir.resolve("out"))
                .release(25)
                .classpath(List.of(cp))
                .processorPath(processorPath)
                .extraOptions(options)
                .build();
        return SessionContext.where(
                Session.defaults().withCacheDir(dir.resolve("cache")), () -> ActionKey.javacClasspathTokens(request));
    }

    @Test
    void a_processing_compile_keys_its_classpaths_meta_inf_but_not_its_bodies(@TempDir Path dir) throws Exception {
        Path pp = AbiJars.jar(dir.resolve("proc.jar"), AbiJars.classReturning(7));
        Path a = jar(dir.resolve("a.jar"), AbiJars.classReturning(1), "p.One\n");
        Path body = jar(dir.resolve("b.jar"), AbiJars.classReturning(2), "p.One\n");
        Path service = jar(dir.resolve("c.jar"), AbiJars.classReturning(1), "p.Two\n");

        List<String> base = tokens(dir.resolve("1"), copy(a, dir.resolve("1/dep.jar")), List.of(pp), List.of());
        List<String> bodyOnly = tokens(dir.resolve("2"), copy(body, dir.resolve("2/dep.jar")), List.of(pp), List.of());
        List<String> moved = tokens(dir.resolve("3"), copy(service, dir.resolve("3/dep.jar")), List.of(pp), List.of());

        assertThat(cpLines(bodyOnly)).as("a body-only change keeps the key").isEqualTo(cpLines(base));
        assertThat(cpLines(moved)).as("a META-INF change moves it").isNotEqualTo(cpLines(base));
    }

    @Test
    void javac_finding_processors_on_the_classpath_keys_it_by_content(@TempDir Path dir) throws Exception {
        Path a = jar(dir.resolve("a.jar"), AbiJars.classReturning(1), "p.One\n");
        Path body = jar(dir.resolve("b.jar"), AbiJars.classReturning(2), "p.One\n");
        List<String> base =
                tokens(dir.resolve("1"), copy(a, dir.resolve("1/dep.jar")), List.of(), List.of("-proc:full"));
        List<String> bodyOnly =
                tokens(dir.resolve("2"), copy(body, dir.resolve("2/dep.jar")), List.of(), List.of("-proc:full"));
        assertThat(cpLines(bodyOnly))
                .as("the processor's body is the compile's behaviour")
                .isNotEqualTo(cpLines(base));
    }

    private static Path copy(Path from, Path to) throws Exception {
        Files.createDirectories(to.getParent());
        return Files.copy(from, to);
    }

    private static List<String> cpLines(List<String> lines) {
        return lines.stream().filter(l -> l.startsWith("cp:")).toList();
    }
}
