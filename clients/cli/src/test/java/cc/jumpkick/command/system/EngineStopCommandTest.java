// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.ScriptedEngine;
import cc.jumpkick.cli.engine.SilentPeer;
import cc.jumpkick.cli.engine.SleepMain;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code jk engine stop}'s human uptime formatting, the chip each outcome settles with, and the
 * rule that a graceful stop never hard-kills an engine for silence.
 */
class EngineStopCommandTest {

    private static final String VERSION = "9.9.9";

    /** For a scripted engine that answers: a loaded host can take seconds to schedule its reply. */
    private static final EngineProbe.Patience QUICK = new EngineProbe.Patience(3_000, 2, Duration.ofMillis(20));

    /** For an engine that never answers: the reply wait only has to elapse, so it can be short. */
    private static final EngineProbe.Patience SILENT = new EngineProbe.Patience(50, 2, Duration.ofMillis(20));

    /** Young for the whole test: a loading or busy coordinator. */
    private static final SilentPeer.Grace YOUNG = new SilentPeer.Grace(
            Duration.ofMinutes(5), Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1));

    /** No startup grace: only workers or CPU can show life, and neither does a sleeping JVM. */
    private static final SilentPeer.Grace NO_GRACE =
            new SilentPeer.Grace(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1));

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jke-");

    private record Settled(int exit, Capture.Streams streams) {}

    private static Settled stop(Path socket, boolean now, SilentPeer.Grace grace) throws Exception {
        return stop(socket, now, QUICK, grace);
    }

    private static Settled stop(Path socket, boolean now, EngineProbe.Patience patience, SilentPeer.Grace grace)
            throws Exception {
        int[] exit = new int[1];
        Capture.Streams streams = NoAnsi.forced(
                () -> Capture.both(() -> exit[0] = new EngineStopCommand().stop(socket, now, patience, grace)));
        return new Settled(exit[0], streams);
    }

    @Test
    void a_drain_whose_ack_misses_the_watchdog_never_hard_kills_a_busy_engine() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process engineJvm = SleepMain.spawn(60_000);
        try (ScriptedEngine engine = new ScriptedEngine(socket, (type, connection) -> switch (type) {
            case EngineProtocol.HELLO -> ScriptedEngine.helloAck(VERSION, engineJvm.pid());
            case EngineProtocol.STATUS -> ScriptedEngine.statusAck(VERSION, engineJvm.pid(), 1, false);
            default -> null; // the bye lands after the watchdog: a GC pause mid-build
        })) {
            Settled settled = stop(socket, false, YOUNG);

            assertThat(settled.exit()).isEqualTo(Exit.SUCCESS);
            assertThat(settled.streams().out()).contains("waiting for 1 in-flight job");
            assertThat(engineJvm.isAlive())
                    .as("an engine draining other terminals' jobs is not killed for a late ack")
                    .isTrue();
            assertThat(engine.received(EngineProtocol.SHUTDOWN)).isEqualTo(1);
        } finally {
            engineJvm.destroyForcibly();
            engineJvm.waitFor();
        }
    }

    @Test
    void an_engine_silent_through_every_probe_is_refused_not_killed() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try (ScriptedEngine ignored = ScriptedEngine.silent(socket)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            Settled settled = stop(socket, false, SILENT, YOUNG);

            assertThat(settled.exit()).isEqualTo(Exit.FAILURE);
            assertThat(settled.streams().err())
                    .contains("pid " + holder.pid())
                    .contains("not killed")
                    .contains("jk engine stop -n");
            assertThat(holder.isAlive()).isTrue();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    /** Waits out the stop's real death wait for a JVM that never exits on request. */
    @Test
    @Tag("integration")
    void now_stops_a_silent_engine_regardless() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try (ScriptedEngine ignored = ScriptedEngine.silent(socket)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            Settled settled = stop(socket, true, SILENT, YOUNG);

            assertThat(settled.exit()).isEqualTo(Exit.SUCCESS);
            assertThat(settled.streams().out()).contains("stopped now");
            assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    void a_holder_with_no_listener_that_shows_life_is_refused_not_killed() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            Settled settled = stop(socket, false, YOUNG);

            assertThat(settled.exit()).isEqualTo(Exit.FAILURE);
            assertThat(settled.streams().err()).contains("pid " + holder.pid()).contains("shows life");
            assertThat(holder.isAlive()).isTrue();
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    @Test
    void a_holder_with_no_listener_and_no_life_is_killed() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));
            // Let the JVM's own start-up CPU settle, so two readings apart show no step.
            SleepMain.awaitIdle(holder);

            Settled settled = stop(socket, false, NO_GRACE);

            assertThat(settled.exit()).isEqualTo(Exit.SUCCESS);
            assertThat(settled.streams().out()).contains("pid " + holder.pid()).contains("killed");
            assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    void an_engine_left_alone_by_a_fleet_stop_is_named_and_is_a_failure() throws Exception {
        EngineFleet.StopResult leftAlone = new EngineFleet.StopResult(
                new EngineFleet.Member(null, null, null, 4242L, false), EngineFleet.Outcome.LEFT_ALONE);

        Capture.Streams settled = NoAnsi.forced(() -> Capture.both(() ->
                assertThat(new EngineStopCommand().report(List.of(leftAlone))).isEqualTo(Exit.FAILURE)));

        assertThat(settled.err()).contains("left alone").contains("pid [4242]").contains("jk engine stop -n");
    }

    @Test
    void uptime_drops_leading_zero_units_but_always_shows_seconds() {
        assertThat(EngineStopCommand.uptime(0)).isEqualTo("0s");
        assertThat(EngineStopCommand.uptime(13_000)).isEqualTo("13s");
        assertThat(EngineStopCommand.uptime((37 * 60 + 13) * 1000L)).isEqualTo("37m 13s");
        assertThat(EngineStopCommand.uptime((3 * 3600 + 37 * 60 + 13) * 1000L)).isEqualTo("3h 37m 13s");
    }

    @Test
    void uptime_full_days_hours_minutes_seconds() {
        long ms = ((14L * 86_400) + (3 * 3600) + (37 * 60) + 13) * 1000L;
        assertThat(EngineStopCommand.uptime(ms)).isEqualTo("14d 3h 37m 13s");
    }

    @Test
    void uptime_keeps_zero_inner_units_once_a_larger_unit_is_present() {
        // 2h 0m 5s — the minutes component stays even though it's zero.
        assertThat(EngineStopCommand.uptime((2 * 3600 + 5) * 1000L)).isEqualTo("2h 0m 5s");
    }

    @Test
    void a_pid_that_is_not_a_number_settles_red_on_stderr() throws Exception {
        Capture.Streams settled = NoAnsi.forced(() -> Capture.both(() ->
                assertThat(new EngineStopCommand().stopByPid("abc", false)).isEqualTo(Exit.FAILURE)));

        assertThat(settled.err()).contains("jk: ! Engine >").contains("not a pid: abc");
        assertThat(settled.out()).isEmpty();
    }

    @Test
    void a_survivor_never_prints_a_cheerful_stopped() throws Exception {
        // Only the pid is read out of the member, so the on-disk identity of a synthetic one is
        // irrelevant — and going through EngineFleet would need a wedged engine on this machine.
        EngineFleet.StopResult survivor = new EngineFleet.StopResult(
                new EngineFleet.Member(null, null, null, 4242L, false), EngineFleet.Outcome.SURVIVED);

        Capture.Streams settled = NoAnsi.forced(() -> Capture.both(() ->
                assertThat(new EngineStopCommand().report(List.of(survivor))).isEqualTo(Exit.FAILURE)));

        assertThat(settled.err()).contains("jk: ! Engine >").contains("did NOT exit: pid [4242]");
        // The green chip is the defect: a failure exit must not carry it on either stream.
        assertThat(settled.err()).doesNotContain("jk: + ");
        assertThat(settled.out()).isEmpty();
    }
}
