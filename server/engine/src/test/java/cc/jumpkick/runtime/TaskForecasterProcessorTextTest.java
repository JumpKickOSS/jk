// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.ClasspathProcessors;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.wire.runtime.TaskForecast;
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
 * {@code jk explain --verbose} names, on a compile step, the annotation processors its processor
 * path registers — each class with the jar it comes from — beside the javac plugins it invokes. A
 * path that registers none leaves the step's text alone.
 */
class TaskForecasterProcessorTextTest {

    private static final JavaCompile.Prediction FULL =
            new JavaCompile.Prediction(JavaCompile.Outcome.FULL, "k", 3, "first compile");

    @Test
    void a_compile_step_names_each_processor_and_its_jar(@TempDir Path tmp) throws IOException {
        Path lombok =
                jar(tmp.resolve("lombok-1.18.42.jar"), "lombok.launch.AnnotationProcessorHider$AnnotationProcessor");
        Path mapstruct = jar(tmp.resolve("mapstruct-processor-1.6.3.jar"), "org.mapstruct.ap.MappingProcessor");
        Path plain = jar(tmp.resolve("guava.jar"));

        TaskForecast.Task step = ForecastSteps.compileStep(
                "compile-main", FULL, false, request(List.of(lombok, plain, mapstruct), List.of()));

        assertThat(step.text())
                .isEqualTo("full compile · 3 sources · first compile · processors: "
                        + "lombok.launch.AnnotationProcessorHider$AnnotationProcessor (lombok-1.18.42.jar), "
                        + "org.mapstruct.ap.MappingProcessor (mapstruct-processor-1.6.3.jar)");
    }

    @Test
    void plugins_come_before_processors_and_an_empty_path_leaves_the_text_alone(@TempDir Path tmp) throws IOException {
        Path gen = jar(tmp.resolve("gen.jar"), "com.example.Gen");

        TaskForecast.Task both = ForecastSteps.compileStep(
                "compile-main", FULL, false, request(List.of(gen), List.of("-Xplugin:ErrorProne -Xep:X:ERROR")));
        assertThat(both.text()).endsWith(" · -Xplugin:ErrorProne · processors: com.example.Gen (gen.jar)");

        TaskForecast.Task none = ForecastSteps.compileStep(
                "compile-main", FULL, false, request(List.of(jar(tmp.resolve("plain.jar"))), List.of()));
        assertThat(none.text()).isEqualTo("full compile · 3 sources · first compile");
        assertThat(ForecastSteps.processorsText(List.of())).isEmpty();
    }

    private static CompileRequest request(List<Path> processorPath, List<String> options) {
        return CompileRequest.builder()
                .sources(List.of(Path.of("A.java")))
                .processorPath(processorPath)
                .extraOptions(options)
                .build();
    }

    private static Path jar(Path file, String... processors) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("com/example/Marker.class"));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            jar.closeEntry();
            if (processors.length > 0) {
                jar.putNextEntry(new JarEntry(ClasspathProcessors.SERVICE));
                jar.write((String.join("\n", processors) + "\n").getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }
}
