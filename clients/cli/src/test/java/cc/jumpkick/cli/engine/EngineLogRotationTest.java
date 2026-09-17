// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A spawn rotates the engine log two generations deep, except when another spawner opened the log
 * within the spawn window: then it appends, so a double spawn keeps the dead engine's last lines.
 */
class EngineLogRotationTest {

    @TempDir
    Path tmp;

    @Test
    void a_spawn_keeps_two_generations_behind_the_fresh_log() throws IOException {
        FakeClock clock = new FakeClock();
        Path log = tmp.resolve("k.log");
        Files.writeString(EngineLogRotation.generation(log, 1), "the engine before\n");
        Files.writeString(log, EngineLogRotation.header("/lib/jk-engine.jar", "installed", clock) + "\ndied here\n");
        clock.advance(Duration.ofMinutes(3));

        assertThat(EngineLogRotation.rotate(log, clock)).as("a fresh file").isTrue();

        assertThat(Files.exists(log)).isFalse();
        assertThat(Files.readString(EngineLogRotation.generation(log, 1))).endsWith("died here\n");
        assertThat(Files.readString(EngineLogRotation.generation(log, 2))).isEqualTo("the engine before\n");
    }

    @Test
    void a_second_spawner_inside_the_window_appends_and_rotates_nothing() throws IOException {
        FakeClock clock = new FakeClock();
        Path log = tmp.resolve("k.log");
        Files.writeString(EngineLogRotation.generation(log, 1), "the dead engine's last lines\n");
        Files.writeString(log, EngineLogRotation.header("/lib/jk-engine.jar", "installed", clock) + "\nserving\n");
        clock.advance(Duration.ofSeconds(3));

        assertThat(EngineLogRotation.rotate(log, clock))
                .as("append to the spawn in progress")
                .isFalse();

        assertThat(Files.readString(log)).endsWith("serving\n");
        assertThat(Files.readString(EngineLogRotation.generation(log, 1))).isEqualTo("the dead engine's last lines\n");
        assertThat(Files.exists(EngineLogRotation.generation(log, 2))).isFalse();
    }

    @Test
    void a_log_without_a_header_or_with_no_log_at_all_starts_fresh() throws IOException {
        FakeClock clock = new FakeClock();
        Path log = tmp.resolve("k.log");
        assertThat(EngineLogRotation.rotate(log, clock)).isTrue();

        Files.writeString(log, "no header here\n");
        assertThat(EngineLogRotation.rotate(log, clock)).isTrue();
        assertThat(Files.readString(EngineLogRotation.generation(log, 1))).isEqualTo("no header here\n");
    }

    @Test
    void the_header_carries_the_instant_the_window_is_measured_from() throws IOException {
        FakeClock clock = new FakeClock();
        Path log = tmp.resolve("k.log");
        Files.writeString(log, EngineLogRotation.header("/lib/jk-engine.jar", "installed", clock) + "\n");
        assertThat(EngineLogRotation.spawnedAt(log)).isEqualTo(clock.millis());
        assertThat(Files.readString(log))
                .startsWith("jk engine: spawning /lib/jk-engine.jar (installed) at 2026-01-01T00:00:00Z");
    }
}
