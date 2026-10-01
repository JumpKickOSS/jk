// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
        // The running-server test plans the shared worker heap; reset the static for the rest of the JVM.
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

    /** The deadline is fixed when drain begins; a second stop or a later displacement keeps it. */
    @Test
    void the_drain_deadline_is_fixed_once_at_drain_entry() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        JkEngineConfig config = JkEngineConfig.DEFAULTS.withJobLimits(JobLimits.DEFAULTS.withDrainDeadlineMs(60_000L));
        EngineServer server = new EngineServer(p, config, "1.0", null);
        assertThat(server.drainDeadlineForTests()).as("not draining").isEqualTo(-1L);
        assertThat(server.claimPlanSlotForTests()).isTrue();
        long before = System.currentTimeMillis();

        server.handleShutdown(ProtoLifecycle.shutdown(false), new BufferedWriter(new StringWriter()));
        long deadline = server.drainDeadlineForTests();
        assertThat(deadline).isBetween(before + 60_000L, System.currentTimeMillis() + 60_000L);
        server.yieldListenersForTests(false);
        server.handleShutdown(ProtoLifecycle.shutdown(false), new BufferedWriter(new StringWriter()));

        assertThat(server.drainDeadlineForTests()).isEqualTo(deadline);
        server.releasePlanSlotForTests();
        server.close();
    }

    @Test
    void a_zero_drain_deadline_drains_without_bound() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        JkEngineConfig config = JkEngineConfig.DEFAULTS.withJobLimits(JobLimits.DEFAULTS.withDrainDeadlineMs(0L));
        EngineServer server = new EngineServer(p, config, "1.0", null);
        assertThat(server.claimPlanSlotForTests()).isTrue();

        server.handleShutdown(ProtoLifecycle.shutdown(false), new BufferedWriter(new StringWriter()));

        assertThat(server.drainStartedForTests()).isTrue();
        assertThat(server.drainDeadlineForTests()).isEqualTo(-1L);
        assertThat(server.drainWaitingForTests()).isTrue();
        server.releasePlanSlotForTests();
        server.close();
    }

    /**
     * A plan slot that is never released — a job stuck past every cancel — does not keep a
     * draining engine alive: past the deadline and the grace after it, the engine shuts down.
     */
    @Test
    void a_slot_held_past_the_drain_deadline_still_ends_the_drain() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        List<String> log = new CopyOnWriteArrayList<>();
        JkEngineConfig config = JkEngineConfig.DEFAULTS.withJobLimits(new JobLimits(0L, 0L, 0L, 100L, 0L, 0L, 200L));
        EngineServer server = new EngineServer(p, config, "1.0", log::add);
        assertThat(server.claimPlanSlotForTests()).isTrue();

        server.handleShutdown(ProtoLifecycle.shutdown(false), new BufferedWriter(new StringWriter()));

        Await.until(Duration.ofSeconds(10), () -> !server.drainWaitingForTests());
        assertThat(log).anyMatch(l -> l.contains("drain deadline passed with 1 job(s) in flight"));
        assertThat(log).anyMatch(l -> l.contains("drain deadline: cancelled 0 job(s)"));
        server.releasePlanSlotForTests();
        server.close();
    }

    /**
     * The watchdog observes zero, then a plan claims its slot before the yield returns. The yield
     * re-reads the count, so that plan is drained and the wait does not finish while it is held.
     */
    @Test
    void a_plan_claimed_after_an_idle_observation_is_drained() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        assertThat(server.claimPlanSlotForTests()).isTrue();
        server.yieldListenersForTests(true);
        assertThat(server.drainStartedForTests()).isTrue();
        assertThat(server.drainWaitingForTests())
                .as("awaitDrainComplete stays in its wait while the slot is held")
                .isTrue();
        server.releasePlanSlotForTests();
        assertThat(server.drainWaitingForTests()).isFalse();
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
