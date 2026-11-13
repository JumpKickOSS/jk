// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The GC log's largest before-occupancy, not region counts. */
class WorkerGcTest {

    @Test
    void the_largest_before_occupancy_wins_and_region_counts_do_not() {
        String log = """
                [0.145s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(256M) 3.456ms
                [0.200s][info][gc,heap] GC(0) Eden regions: 24->0(24)
                [1.002s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 128M->40M(256M) 8.1ms
                [0.032s][info][gc] GC(2) Garbage Collection (Warmup) 16M(2%)->8M(1%) 1.2ms
                [2.000s][info][gc] GC(3) Pause Young (Allocation Failure) 512K->128K(256M) 1.0ms
                """;
        assertThat(WorkerGc.peak(log)).isEqualTo(128L << 20);
    }

    @Test
    void gigabyte_sizes_and_an_empty_log_are_read() {
        assertThat(WorkerGc.peak("[gc] GC(0) Pause Full (System.gc()) 1.5G->800M(2G) 20ms"))
                .isEqualTo((long) (1.5 * (1L << 30)));
        assertThat(WorkerGc.peak("")).isZero();
        assertThat(WorkerGc.peak((String) null)).isZero();
    }

    /** The JVM's default rotation moved the empty log aside as {@code <log>.0}, which nothing deleted. */
    @Test
    void the_log_flag_turns_rotation_off() {
        Path log = Path.of("/t/gc-1.log");
        assertThat(WorkerGc.flag(log)).endsWith(log.toAbsolutePath() + "::filecount=0");
    }

    @Test
    void the_sweep_drops_day_old_logs_and_keeps_fresh_ones(@TempDir Path dir) throws Exception {
        Path old = Files.createFile(dir.resolve("gc-1.log.0"));
        Path fresh = Files.createFile(dir.resolve("gc-2.log"));
        Path other = Files.createFile(dir.resolve("keep.txt"));
        Instant now = Instant.now();
        FileTime twoDaysAgo = FileTime.from(now.minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(old, twoDaysAgo);
        Files.setLastModifiedTime(other, twoDaysAgo);
        WorkerGc.sweep(dir, now.minus(WorkerGc.STALE));
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(other).exists();
    }
}
