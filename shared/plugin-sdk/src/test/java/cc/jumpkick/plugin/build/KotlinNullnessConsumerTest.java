// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
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

    @Test
    void a_child_that_stops_using_cpu_is_given_up_on(@TempDir Path temp) throws Exception {
        Path sleeper = temp.resolve("Sleeper.java");
        Files.writeString(sleeper, """
                class Sleeper {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(600_000);
                    }
                }
                """);
        Process process = new ProcessBuilder(javaExecutable().toString(), sleeper.toString()).start();
        try {
            long start = System.nanoTime();
            assertThat(awaitWhileWorking(process, Duration.ofSeconds(3))).contains("used no CPU for 3 seconds");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMinutes(2));
        } finally {
            process.destroyForcibly();
        }
    }

    private static CompileResult compile(Path temp, Path source) throws Exception {
        Path output = Files.createDirectories(temp.resolve("classes"));
        Path log = temp.resolve("compiler.log");
        ProcessBuilder builder = new ProcessBuilder(
                        javaExecutable().toString(),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "@" + writeArgfile(temp, output, source))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // Windows caps a command line at 32767 characters and this test classpath is most of
        // that on its own, so neither copy of it is spelled there: the launcher reads CLASSPATH,
        // the compiler reads its argfile.
        builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        Process process = builder.start();
        String stalled = awaitWhileWorking(process, STALL);
        if (stalled != null) {
            process.destroyForcibly();
            throw new IOException("Kotlin compiler " + stalled + "; output:\n" + Files.readString(log));
        }
        return new CompileResult(process.exitValue(), Files.readString(log));
    }

    /** A compiler that has used no CPU for this long is hung, however loaded the host. */
    private static final Duration STALL = Duration.ofSeconds(30);

    /** The most a working compiler is given: past this it is spinning, not compiling. */
    private static final Duration CEILING = Duration.ofMinutes(10);

    /**
     * Wait for {@code process} while it keeps using CPU: a full build starves it of time, not of
     * progress. Null when it exited; otherwise why it was given up on. Where the platform reports no
     * CPU time, only the ceiling applies.
     */
    private static @Nullable String awaitWhileWorking(Process process, Duration stall) throws InterruptedException {
        long start = System.nanoTime();
        long lastProgress = start;
        Duration lastCpu = Duration.ZERO;
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            long now = System.nanoTime();
            Optional<Duration> cpu = process.toHandle().info().totalCpuDuration();
            if (cpu.isEmpty() || cpu.get().compareTo(lastCpu) > 0) {
                lastCpu = cpu.orElse(lastCpu);
                lastProgress = now;
            }
            if (now - lastProgress > stall.toNanos()) {
                return "used no CPU for " + stall.toSeconds() + " seconds";
            }
            if (now - start > CEILING.toNanos()) return "did not exit within " + CEILING.toMinutes() + " minutes";
        }
        return null;
    }

    /**
     * The compiler's arguments, in a file it reads itself. Paths go in with forward slashes:
     * Windows accepts them, and a backslash inside a quoted argfile value is an escape the
     * compiler's parser swallows.
     */
    private static Path writeArgfile(Path temp, Path output, Path source) throws IOException {
        Path argfile = temp.resolve("kotlinc.args");
        Files.writeString(
                argfile,
                String.join(
                        "\n",
                        "-Xjspecify-annotations=strict",
                        "-classpath",
                        quote(System.getProperty("java.class.path")),
                        "-d",
                        quote(output.toString()),
                        quote(source.toString())),
                StandardCharsets.UTF_8);
        return argfile;
    }

    private static String quote(String value) {
        return '"' + value.replace(File.separatorChar, '/') + '"';
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java");
    }

    private record CompileResult(int exit, String output) {}
}
