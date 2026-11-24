// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.copyTree;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.jk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.Run;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.JkVersion;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native client's {@code jk watch compile}: a save starts the next pass promptly. The watch's
 * own tests run on the JVM; this one runs the native image, which binds the watch itself (on macOS
 * FSEvents, whose callback the image must register) and would fall back to the JDK's watch, which
 * polls there every 2 s, when it cannot.
 *
 * <p>The client is the native binary the build links in {@code clients/cli}, which the build hands
 * this suite as {@code -Djk.native.client} and links before the suite starts, when it reports this
 * tree's version. Never {@code target/dist/jk} or the {@code jk} on {@code PATH}: the same build
 * rewrites both after its tests. A forked JVM client starts the engine in a private {@code JK_HOME}
 * seeded with the sandbox's, as {@link VerbMatrixNoopE2eTest} does, since only a JVM client hands
 * the engine the test run's worker jars; the native client then runs against that engine.
 */
// Slow tier: a quiet wait of 300 ms before each of five saves, beside a forked engine.
@Tag("slow")
class NativeWatchLatencyE2eTest {

    private static final int SAVES = 5;

    /** Far below the 2 s the JDK's watch polls at on macOS, with room for a loaded host. */
    private static final long PROMPT_MILLIS = 750;

    @Test
    void each_save_starts_a_pass_of_the_native_watch_promptly(@TempDir Path tmp) throws Exception {
        Path client = nativeClient();
        assumeTrue(client != null, "no native jk of version " + JkVersion.VERSION + " from this build");
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");
        Path home = tmp.resolve("home");
        Path engineHome = home.resolve("lib").resolve(EngineInstall.BIN_NAME);
        copyTree(sandboxEngine.get().root(), engineHome);
        Files.deleteIfExists(engineHome.resolve(EngineInstall.SHELF_NAME));

        Path project = project(tmp.resolve("app"));
        Path source = project.resolve("src/main/java/demo/Main.java");
        Process watch = null;
        try {
            Run compile = jk(home, project, "compile");
            assertThat(compile.exit()).as(compile.output()).isZero();
            watch = start(client, home, project, "watch", "compile", "--debounce-ms", "0");
            Lines lines = new Lines(watch.getInputStream());
            lines.await("Ctrl-C stops", TimeUnit.MINUTES.toNanos(2));
            // The loop opens its watch just after it says so: save until a save is seen.
            boolean watching = false;
            for (int i = 0; i < 5 && !watching; i++) {
                save(source, "warm " + i);
                watching = lines.find("change detected", TimeUnit.SECONDS.toNanos(5)) != null;
            }
            assertThat(watching).as("a save woke the loop:%n%s", lines.all()).isTrue();
            lines.await("Compiled", TimeUnit.MINUTES.toNanos(1));

            List<Long> detected = new ArrayList<>();
            List<Long> passes = new ArrayList<>();
            for (int i = 0; i < SAVES; i++) {
                lines.quiet(TimeUnit.MILLISECONDS.toNanos(300));
                long saved = System.nanoTime();
                save(source, "save " + i);
                detected.add(TimeUnit.NANOSECONDS.toMillis(
                        lines.await("change detected", TimeUnit.SECONDS.toNanos(10)) - saved));
                passes.add(TimeUnit.NANOSECONDS.toMillis(lines.await("Compiled", TimeUnit.MINUTES.toNanos(1)) - saved));
            }
            // The median, not the slowest: a polling watch is slow on every save, while one stall of
            // a loaded host (a gate running every suite at once) says nothing about the watch.
            List<Long> sorted = new ArrayList<>(detected);
            Collections.sort(sorted);
            assertThat(sorted.get(sorted.size() / 2))
                    .as("median save to pass start of %s ms, to compiled %s ms", detected, passes)
                    .isLessThan(PROMPT_MILLIS);
        } finally {
            if (watch != null) {
                watch.destroy();
                if (!watch.waitFor(10, TimeUnit.SECONDS)) watch.destroyForcibly();
            }
            jk(home, project, "engine", "stop", "--now");
        }
    }

    /** A module with one class and no dependencies. */
    private static Path project(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(
                dir.resolve("jk.toml"), """
                group = "demo"
                name = "app"
                version = "0.1.0"
                java = %d
                """.formatted(Runtime.version().feature()));
        save(dir.resolve("src/main/java/demo/Main.java"), "first");
        return dir;
    }

    private static void save(Path source, String note) throws IOException {
        Files.writeString(source, "package demo;\n\npublic class Main {} // " + note + "\n");
    }

    /** The native client this build linked, when it reports this tree's version; null otherwise. */
    private static @Nullable Path nativeClient() throws IOException, InterruptedException {
        String named = System.getProperty("jk.native.client");
        if (named == null || named.isBlank()) return null;
        Path client = Path.of(named);
        return Files.isRegularFile(client) && ofThisTree(client) ? client : null;
    }

    private static boolean ofThisTree(Path client) throws IOException, InterruptedException {
        Processes.Captured version = Processes.capture(
                new ProcessBuilder(client.toString(), "--version").redirectErrorStream(true), Duration.ofSeconds(30));
        return version.output().trim().equals("jk " + JkVersion.VERSION);
    }

    private static Process start(Path client, Path home, Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(client.toString(), "--no-ansi", "--no-progress"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_HOME", home.toString());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().remove("JK_ENGINE_EXE");
        return pb.start();
    }

    /** The client's output lines, each with the {@link System#nanoTime} it arrived at. */
    private static final class Lines {
        private record Line(long nanos, String text) {}

        private final BlockingQueue<Line> queue = new LinkedBlockingQueue<>();
        private final List<String> all = new ArrayList<>();

        Lines(InputStream in) {
            Thread.ofPlatform().daemon().name("watch-output").start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) queue.add(new Line(System.nanoTime(), line));
                } catch (IOException ended) {
                    // The client exited.
                }
            });
        }

        /** When the next line holding {@code text} arrived; null when none does within {@code nanos}. */
        @Nullable
        Long find(String text, long nanos) throws InterruptedException {
            long deadline = System.nanoTime() + nanos;
            while (true) {
                long remaining = deadline - System.nanoTime();
                Line line = remaining > 0 ? queue.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (line == null) return null;
                keep(line);
                if (line.text().contains(text)) return line.nanos();
            }
        }

        /** As {@link #find}, failing with what the client said when no such line arrives. */
        long await(String text, long nanos) throws InterruptedException {
            Long at = find(text, nanos);
            assertThat(at).as("a line holding \"%s\" in:%n%s", text, all()).isNotNull();
            return at == null ? 0 : at;
        }

        /** Read what arrives until none has for {@code nanos}. */
        void quiet(long nanos) throws InterruptedException {
            for (Line line; (line = queue.poll(nanos, TimeUnit.NANOSECONDS)) != null; ) keep(line);
        }

        private void keep(Line line) {
            synchronized (all) {
                all.add(line.text());
            }
        }

        String all() {
            synchronized (all) {
                return String.join("\n", all);
            }
        }
    }
}
