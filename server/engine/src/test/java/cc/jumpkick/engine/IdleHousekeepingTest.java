// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.TestClassWalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The idle boundary releases per-build memos, but the class-wall buffer is the only cross-build
 * test ETA source when history is off, so it survives the boundary then. It also retires
 * OutOfMemoryError heap dumps once they are older than the retention window.
 */
class IdleHousekeepingTest {

    @Test
    void heap_dumps_older_than_the_retention_window_are_deleted_and_fresh_ones_kept(@TempDir Path engineDir)
            throws IOException {
        long now = 1_800_000_000_000L;
        Path stale = engineDir.resolve("0123456789abcdef.hprof");
        Path fresh = engineDir.resolve("fedcba9876543210.hprof");
        Path log = engineDir.resolve("0123456789abcdef.log");
        Files.writeString(stale, "old");
        Files.writeString(fresh, "new");
        Files.writeString(log, "old but not a dump");
        long beyond = now - IdleHousekeeping.HEAP_DUMP_RETENTION.toMillis() - 1;
        Files.setLastModifiedTime(stale, FileTime.fromMillis(beyond));
        Files.setLastModifiedTime(log, FileTime.fromMillis(beyond));
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(now - 60_000));

        assertThat(IdleHousekeeping.pruneHeapDumps(engineDir, now)).isEqualTo(1);

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        assertThat(log).as("only dumps are retired here; logs rotate on spawn").exists();
        assertThat(IdleHousekeeping.pruneHeapDumps(engineDir.resolve("missing"), now))
                .isZero();
    }

    @Test
    void the_idle_boundary_keeps_test_walls_when_history_is_off() {
        TestClassWalls.put("/w/idle-test", Map.of("com.example.SlowTest", 1_200L));

        IdleHousekeeping.dropHeapResidue(false);
        assertThat(TestClassWalls.get("/w/idle-test"))
                .as("no harvest wrote these walls anywhere else")
                .containsEntry("com.example.SlowTest", 1_200L);

        IdleHousekeeping.dropHeapResidue(true);
        assertThat(TestClassWalls.get("/w/idle-test"))
                .as("with history on the finish path already harvested them")
                .isEmpty();
    }
}
