// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.util.AotSettings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The two entry paths into drain, isolated from each other. A takeover fires both — the successor
 * repoints the endpoint (the watchdog sees it) <em>and</em> sends SHUTDOWN — so a takeover test
 * stays green with either path cut. Each test here drives exactly one path and fails when that
 * path no longer reaches {@code enterDrain()}.
 */
class EngineServerDrainTest {

    // UDS paths are capped at ~104 bytes (macOS/BSD) / ~108 (Linux) — @TempDir nests too deep
    // under Gradle's build dir. Short-path temp dirs under the system temp root instead.
    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path root =
                Files.isDirectory(Path.of("/tmp")) ? Path.of("/tmp") : Path.of(System.getProperty("java.io.tmpdir"));
        Path dir = Files.createTempDirectory(root, "jkd-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanup() {
        // handleShutdown/yieldListeners suppress AOT training process-wide; the running-server
        // test also plans the shared worker heap. Reset both statics for the rest of the JVM.
        AotSettings.clearTrainingSuppressionForTests();
        JvmOptions.resetSharedPlanForTests();
        for (Path dir : tempDirs) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
            } catch (IOException | UncheckedIOException ignored) {
                // engine teardown may still be deleting its own files — best-effort
            }
        }
    }

    /** SHUTDOWN with a plan in flight must enter drain, not just answer bye. */
    @Test
    void shutdown_with_active_plans_enters_drain() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        assertThat(server.claimPlanSlotForTests()).isTrue();
        StringWriter out = new StringWriter();
        BufferedWriter writer = new BufferedWriter(out);

        server.handleShutdown(ProtoLifecycle.shutdown(false), writer);
        writer.flush();

        assertThat(server.drainStartedForTests())
                .as("the shutdown arm must reach enterDrain(), the reporter's one starter")
                .isTrue();
        assertThat(out.toString()).contains("\"plans\":1").contains("\"draining\":true");
        server.releasePlanSlotForTests();
        server.close();
    }

    /**
     * The displacement watchdog must enter drain on its own — a predecessor whose socket read
     * raced never receives the successor's SHUTDOWN line, so the repointed endpoint is the only
     * signal it gets.
     */
    @Test
    @Tag("integration")
    void a_displacement_tick_enters_drain_without_a_shutdown_line() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread runner = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                "test-engine-server");
        runner.setDaemon(true);
        runner.start();
        try {
            waitUntil(Duration.ofSeconds(10), () -> Files.isRegularFile(EnginePaths.endpoint(p)));
            assertThat(server.claimPlanSlotForTests()).isTrue();
            assertThat(server.drainStartedForTests()).isFalse();

            // A successor repointed the endpoint; no SHUTDOWN line ever arrives.
            Files.writeString(EnginePaths.endpoint(p), "someone-else.gen2.sock");
            assertThat(server.displacementTick()).isTrue();

            assertThat(server.drainStartedForTests())
                    .as("the watchdog path must reach enterDrain() on its own")
                    .isTrue();
        } finally {
            server.releasePlanSlotForTests();
            server.close();
            runner.join(10_000);
        }
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }
}
