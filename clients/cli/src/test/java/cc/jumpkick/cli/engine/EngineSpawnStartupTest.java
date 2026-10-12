// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineSpawn.StartResult;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.testing.Sleepers;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A spawned child that exits cleanly lost the startup election to an engine still booting: the
 * wait goes on for that engine until the ceiling. A child that exits with an error crashed, and
 * is reported at once.
 */
class EngineSpawnStartupTest {

    private static final String VERSION = "9.9.9";

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkb-");

    private EnginePaths.Paths paths() throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Files.createDirectories(paths.dir());
        return paths;
    }

    private static Process exited(int code) throws Exception {
        Process p = new ProcessBuilder(Sleepers.exits(code)).start();
        p.waitFor();
        return p;
    }

    @Test
    void a_child_that_yields_the_election_is_waited_through_until_the_winner_serves() throws Exception {
        EnginePaths.Paths paths = paths();
        Path socket = EnginePaths.activeSocket(paths);
        Process loser = exited(0);
        // The winner binds a moment after the loser has gone: the healthy loser, not a crash.
        Thread winner = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(700);
                ScriptedEngine engine =
                        new ScriptedEngine(socket, (type, connection) -> ScriptedEngine.helloAck(VERSION, 4242));
                Thread.sleep(5_000);
                engine.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            long start = System.nanoTime();
            StartResult r = EngineSpawn.awaitStartup(paths, VERSION, Duration.ofSeconds(10), loser);

            assertThat(r.outcome()).isEqualTo(StartResult.Outcome.UP);
            assertThat(Objects.requireNonNull(r.handshake()).pid()).isEqualTo(4242);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("waited for the winner rather than classifying the exit at once")
                    .isGreaterThanOrEqualTo(Duration.ofMillis(600));
        } finally {
            winner.interrupt();
            winner.join();
        }
    }

    @Test
    void a_child_that_crashes_is_reported_at_once() throws Exception {
        EnginePaths.Paths paths = paths();
        Process crashed = exited(3);

        long start = System.nanoTime();
        StartResult r = EngineSpawn.awaitStartup(paths, VERSION, Duration.ofSeconds(30), crashed);

        assertThat(r.outcome()).isEqualTo(StartResult.Outcome.CHILD_EXITED);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void a_yielding_child_whose_winner_never_serves_is_an_exit_at_the_ceiling() throws Exception {
        EnginePaths.Paths paths = paths();
        Process loser = exited(0);

        long start = System.nanoTime();
        StartResult r = EngineSpawn.awaitStartup(paths, VERSION, Duration.ofMillis(800), loser);

        assertThat(r.outcome()).isEqualTo(StartResult.Outcome.CHILD_EXITED);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(800));
    }
}
