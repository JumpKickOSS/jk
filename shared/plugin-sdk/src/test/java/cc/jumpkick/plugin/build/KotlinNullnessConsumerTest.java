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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kotlin sees the SDK's JSpecify null contracts as strict nullable types. */
class KotlinNullnessConsumerTest {

    @Test
    @Tag("integration") // forks kotlinc
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
    @Tag("integration") // forks kotlinc
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
    void a_child_that_stops_using_cpu_is_given_up_on() throws Exception {
        Process process = new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Sleeper.class.getName())
                .start();
        try {
            assertThat(cpuTime(process)).as("child CPU time").isPresent();
            long start = System.nanoTime();
            assertThat(awaitWhileWorking(process, Duration.ofMillis(500), Duration.ofMinutes(1)))
                    .contains("used no CPU for 500 ms");
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
        String stalled = awaitWhileWorking(process, STALL, CEILING);
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
     * CPU a working compiler burns that an idle JVM does not. A sleeping HotSpot still accrues a
     * few milliseconds; below this step the process is not making progress.
     */
    private static final Duration CPU_STEP = Duration.ofMillis(50);

    /**
     * Wait for {@code process} while it keeps using CPU: a full build starves it of time, not of
     * progress. Null when it exited; otherwise why it was given up on. Where no CPU time can be
     * read, only the ceiling applies. CPU is sampled five times per {@code stall}, at most once a
     * second.
     */
    private static @Nullable String awaitWhileWorking(Process process, Duration stall, Duration ceiling)
            throws InterruptedException {
        long start = System.nanoTime();
        long lastProgress = start;
        long pollMillis = Math.min(1000, stall.toMillis() / 5);
        Duration lastCpu = Duration.ZERO;
        boolean cpuKnown = false;
        while (!process.waitFor(pollMillis, TimeUnit.MILLISECONDS)) {
            long now = System.nanoTime();
            Optional<Duration> cpu = cpuTime(process);
            if (cpu.isPresent()) {
                cpuKnown = true;
                if (cpu.get().minus(lastCpu).compareTo(CPU_STEP) >= 0) {
                    lastCpu = cpu.get();
                    lastProgress = now;
                }
            }
            if (cpuKnown && now - lastProgress > stall.toNanos()) {
                return "used no CPU for " + stall.toMillis() + " ms";
            }
            if (now - start > ceiling.toNanos()) return "did not exit within " + ceiling.toMinutes() + " minutes";
        }
        return null;
    }

    /**
     * Cumulative CPU time of {@code process}. The process handle on macOS leaves this empty for
     * every process but the current one, so {@code ps} supplies it there.
     */
    private static Optional<Duration> cpuTime(Process process) throws InterruptedException {
        Optional<Duration> reported = process.toHandle().info().totalCpuDuration();
        if (reported.isPresent() || Os.isWindows()) return reported;
        return cpuTimeFromPs(process.pid());
    }

    private static Optional<Duration> cpuTimeFromPs(long pid) throws InterruptedException {
        Process ps;
        try {
            ps = new ProcessBuilder("ps", "-o", "time=", "-p", Long.toString(pid))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            return Optional.empty();
        }
        if (!ps.waitFor(2, TimeUnit.SECONDS)) {
            ps.destroyForcibly();
            return Optional.empty();
        }
        String out;
        try {
            out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return Optional.empty();
        }
        if (ps.exitValue() != 0 || out.isEmpty()) return Optional.empty();
        return Optional.of(parsePsTime(out));
    }

    /** {@code ps -o time=} value: {@code [[dd-]hh:]mm:ss[.fraction]}. */
    private static Duration parsePsTime(String text) {
        String t = text.trim();
        long days = 0;
        int dash = t.indexOf('-');
        if (dash >= 0) {
            days = Long.parseLong(t.substring(0, dash));
            t = t.substring(dash + 1);
        }
        String[] parts = t.split(":");
        long hours = 0;
        long minutes = 0;
        double seconds;
        if (parts.length == 1) {
            seconds = Double.parseDouble(parts[0]);
        } else if (parts.length == 2) {
            minutes = Long.parseLong(parts[0]);
            seconds = Double.parseDouble(parts[1]);
        } else if (parts.length == 3) {
            hours = Long.parseLong(parts[0]);
            minutes = Long.parseLong(parts[1]);
            seconds = Double.parseDouble(parts[2]);
        } else {
            throw new IllegalArgumentException("ps time: " + text);
        }
        long wholeSeconds = (long) seconds;
        long nanos = Math.round((seconds - wholeSeconds) * 1_000_000_000d);
        return Duration.ofDays(days)
                .plusHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(wholeSeconds)
                .plusNanos(nanos);
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

    /** A child JVM that starts and then uses no CPU. */
    static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(600_000);
        }
    }
}
