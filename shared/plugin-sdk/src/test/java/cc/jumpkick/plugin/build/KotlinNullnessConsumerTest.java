// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kotlin sees the SDK's JSpecify null contracts as strict nullable types. */
class KotlinNullnessConsumerTest {

    @Test
    void nullable_sdk_results_compile_as_kotlin_nullable_types(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("ValidConsumer.kt");
        Files.writeString(source, """
                package consumer

                import cc.jumpkick.plugin.build.ImageResult
                import cc.jumpkick.plugin.build.ProjectFacts
                import cc.jumpkick.plugin.protocol.PluginSpec
                import java.nio.file.Path

                fun workdir(spec: PluginSpec): Path? = spec.workdir()
                fun imageReference(result: ImageResult): String? = result.reference()
                fun mainClass(facts: ProjectFacts): String? = facts.mainClass()
                """);

        CompileResult result = compile(temp, source);

        assertThat(result.exit()).isZero();
    }

    @Test
    void nullable_sdk_results_cannot_flow_into_kotlin_non_null_types(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("InvalidConsumer.kt");
        Files.writeString(source, """
                package consumer

                import cc.jumpkick.plugin.protocol.PluginSpec
                import java.nio.file.Path

                fun workdir(spec: PluginSpec): Path = spec.workdir()
                """);

        CompileResult result = compile(temp, source);

        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains("return type mismatch");
    }

    private static CompileResult compile(Path temp, Path source) throws Exception {
        Path output = Files.createDirectories(temp.resolve("classes"));
        Path log = temp.resolve("compiler.log");
        Process process = new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-Xjspecify-annotations=strict",
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        output.toString(),
                        source.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Kotlin compiler did not exit within 30 seconds");
        }
        return new CompileResult(process.exitValue(), Files.readString(log));
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java");
    }

    private record CompileResult(int exit, String output) {}
}
