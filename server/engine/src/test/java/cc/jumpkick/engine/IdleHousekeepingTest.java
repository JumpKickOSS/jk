// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.runtime.base.TestClassWalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The idle boundary releases per-build memos, but the class-wall buffer is the only cross-build
 * test ETA source when history is off, so it survives the boundary then. It also retires
 * OutOfMemoryError heap dumps once they are older than the retention window, and leaves every
 * shared store alone while the engine drains for a successor.
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

    @Test
    void a_draining_engine_leaves_the_queued_prune_and_old_dumps_to_its_successor(
            @TempDir Path home, @TempDir Path roots) throws Exception {
        System.setProperty("jk.env.JK_HOME", home.toAbsolutePath().toString());
        System.setProperty("jk.env.JK_AUTO_PRUNE", "true");
        try {
            Path cache = roots.resolve("cache");
            Files.createDirectories(cache.resolve("actions/keys"));
            Files.writeString(cache.resolve("actions/keys/task1"), "x");
            Path engineDir = Files.createDirectories(roots.resolve("engine"));
            Path dump = engineDir.resolve("0123456789abcdef.hprof");
            Files.writeString(dump, "old");
            Files.setLastModifiedTime(dump, FileTime.fromMillis(0));
            List<String> log = new CopyOnWriteArrayList<>();
            AtomicBoolean drained = new AtomicBoolean();
            IdleHousekeeping housekeeping = new IdleHousekeeping(
                    new AtomicInteger(0),
                    new ReentrantReadWriteLock(),
                    new JkHistoryConfig(false, 30, 512),
                    new BuildJournal(roots.resolve("builds")),
                    () -> roots.resolve("metrics.jsonl"),
                    engineDir,
                    System::currentTimeMillis,
                    log::add,
                    () -> false,
                    () -> true,
                    () -> drained.set(true));
            housekeeping.maybeEnqueuePrune(cache);

            housekeeping.maybeIdleBoundary();

            assertThat(drained).as("the last plan still ends the drain").isTrue();
            assertThat(log).noneMatch(line -> line.contains("prune"));
            assertThat(dump).as("the successor's boundary retires it").exists();
        } finally {
            System.clearProperty("jk.env.JK_HOME");
            System.clearProperty("jk.env.JK_AUTO_PRUNE");
        }
    }
}
