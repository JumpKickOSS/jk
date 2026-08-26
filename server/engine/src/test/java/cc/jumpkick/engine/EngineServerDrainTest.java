// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.util.AotSettings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The two entry paths into drain, isolated from each other. A takeover fires both — the successor
 * repoints the endpoint (the watchdog sees it) <em>and</em> sends SHUTDOWN — so a takeover test
 * stays green with either path cut. Each test here drives exactly one path and fails when that
 * path no longer reaches {@code enterDrain()}.
 */
class EngineServerDrainTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkd-");

    @AfterEach
    void cleanup() {
        // handleShutdown/yieldListeners suppress AOT training process-wide; the running-server
        // test also plans the shared worker heap. Reset both statics for the rest of the JVM.
        AotSettings.clearTrainingSuppressionForTests();
        JvmOptions.resetSharedPlanForTests();
    }

    /** SHUTDOWN with a plan in flight must enter drain, not just answer bye. */
    @Test
    void shutdown_with_active_plans_enters_drain() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
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
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
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
            Await.until(Duration.ofSeconds(10), () -> Files.isRegularFile(EnginePaths.endpoint(p)));
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
}
