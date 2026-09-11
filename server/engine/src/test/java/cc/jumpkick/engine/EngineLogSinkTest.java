// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.testing.FakeClock;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The log rolls once at the cap, keeps one generation, and never rolls a file it no longer owns. */
class EngineLogSinkTest {

    private static final long CAP = 64 * 1024;

    @Test
    void writing_past_the_cap_rolls_exactly_once_and_leaves_a_small_current_file(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("engine.log");
        Files.writeString(log, "jk engine: spawning header\n");
        FakeClock clock = new FakeClock();
        EngineLogSink sink = new EngineLogSink(log, CAP, clock);
        try (PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8)) {
            String line = "x".repeat(1023);
            long lines = (CAP / 1024) + (CAP / 1024) / 2; // one and a half caps, in 1 KiB lines
            for (long i = 0; i < lines; i++) out.println(line);
        }
        Path previous = dir.resolve("engine.log.1");
        assertThat(sink.rolls()).isEqualTo(1);
        assertThat(sink.lastRolledAtMillis()).isEqualTo(clock.millis());
        assertThat(previous).isRegularFile();
        assertThat(Files.readString(previous)).startsWith("jk engine: spawning header");
        assertThat(Files.size(previous)).isGreaterThanOrEqualTo(CAP);
        assertThat(log).isRegularFile();
        assertThat(Files.size(log)).isLessThan(CAP);
        assertThat(EngineLogSink.sizeOf(log)).isEqualTo(Files.size(log));
    }

    @Test
    void a_second_roll_replaces_the_kept_generation(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("engine.log");
        EngineLogSink sink = new EngineLogSink(log, CAP, Clock.SYSTEM);
        try (PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8)) {
            for (long written = 0; written < 3 * CAP; written += 1024) out.println("y".repeat(1023));
        }
        assertThat(sink.rolls()).isEqualTo(3);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("one generation kept, never a .2")
                    .containsExactlyInAnyOrder("engine.log", "engine.log.1");
        }
    }

    @Test
    void a_sink_rotated_aside_by_a_spawner_leaves_the_successors_log_alone(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("engine.log");
        Path previous = dir.resolve("engine.log.1");
        EngineLogSink sink = new EngineLogSink(log, CAP, Clock.SYSTEM);
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        out.println("predecessor, still draining");
        // The successor's spawner: rotate the live log aside and start a fresh one at the path.
        Files.move(log, previous, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(log, "jk engine: spawning successor\n");

        for (long written = 0; written < 2 * CAP; written += 1024) out.println("z".repeat(1023));
        out.close();

        assertThat(sink.rolls())
                .as("no roll: the path no longer names this sink's file")
                .isZero();
        assertThat(Files.readString(log)).isEqualTo("jk engine: spawning successor\n");
        assertThat(Files.readString(previous)).startsWith("predecessor, still draining");
    }

    @Test
    void zero_cap_never_rolls(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("engine.log");
        EngineLogSink sink = new EngineLogSink(log, 0, Clock.SYSTEM);
        try (PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8)) {
            for (long written = 0; written < 2 * CAP; written += 1024) out.println("w".repeat(1023));
        }
        assertThat(sink.rolls()).isZero();
        assertThat(sink.lastRolledAtMillis()).isEqualTo(-1);
        assertThat(dir.resolve("engine.log.1")).doesNotExist();
        assertThat(EngineLogSink.sizeOf(dir.resolve("missing.log"))).isEqualTo(-1);
    }
}
