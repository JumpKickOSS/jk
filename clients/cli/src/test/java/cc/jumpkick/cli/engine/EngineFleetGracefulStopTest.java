// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A fleet stop without {@code --now} never kills an engine for silence: one that accepts
 * connections and answers nothing, or one with no listener whose process shows life, is left
 * alone and named; only a lifeless holder with no listener is killed.
 */
class EngineFleetGracefulStopTest {

    private static final EngineProbe.Patience QUICK = new EngineProbe.Patience(200, 1, Duration.ofMillis(20));

    /** Young for the whole test: a loading or busy coordinator. */
    private static final SilentPeer.Grace YOUNG = new SilentPeer.Grace(
            Duration.ofMinutes(5), Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1));

    /** No startup grace: only workers or CPU can show life, and neither does a sleeping JVM. */
    private static final SilentPeer.Grace NO_GRACE =
            new SilentPeer.Grace(Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1));

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkf-");

    private static EngineFleet.Member unresponsive(Path socket, long pid) {
        return new EngineFleet.Member(null, socket, null, pid, false);
    }

    @Test
    void a_silent_engine_is_left_alone_not_killed() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try (ScriptedEngine engine = ScriptedEngine.silent(socket)) {
            Files.writeString(EnginePaths.pidFor(socket), Long.toString(holder.pid()));

            EngineFleet.StopResult result = EngineFleet.stop(unresponsive(socket, holder.pid()), false, QUICK, YOUNG);

            assertThat(result.outcome()).isEqualTo(EngineFleet.Outcome.LEFT_ALONE);
            assertThat(holder.isAlive()).isTrue();
            assertThat(engine.received("shutdown")).isEqualTo(QUICK.probes());
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }

    @Test
    void a_holder_with_no_listener_that_shows_life_is_left_alone() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try {
            EngineFleet.StopResult result = EngineFleet.stop(unresponsive(socket, holder.pid()), false, QUICK, YOUNG);

            assertThat(result.outcome()).isEqualTo(EngineFleet.Outcome.LEFT_ALONE);
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
            // Let the JVM's own start-up CPU settle, so two readings apart show no step.
            Thread.sleep(1_500);

            EngineFleet.StopResult result =
                    EngineFleet.stop(unresponsive(socket, holder.pid()), false, QUICK, NO_GRACE);

            assertThat(result.outcome()).isEqualTo(EngineFleet.Outcome.KILLED);
            assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    void now_stops_a_silent_engine_regardless() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        Process holder = SleepMain.spawn(60_000);
        try (ScriptedEngine ignored = ScriptedEngine.silent(socket)) {
            EngineFleet.StopResult result = EngineFleet.stop(unresponsive(socket, holder.pid()), true, QUICK, YOUNG);

            assertThat(result.outcome()).isEqualTo(EngineFleet.Outcome.KILLED);
            assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            holder.destroyForcibly();
        }
    }
}
