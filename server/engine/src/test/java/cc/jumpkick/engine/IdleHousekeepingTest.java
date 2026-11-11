// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildMetrics;
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
import java.util.concurrent.atomic.AtomicLong;
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
        TestClassWalls.put("/w/idle-test", TaskNames.RUN_TESTS, Map.of("com.example.SlowTest", 1_200L));

        IdleHousekeeping.dropHeapResidue(false);
        assertThat(TestClassWalls.get("/w/idle-test", TaskNames.RUN_TESTS))
                .as("no harvest wrote these walls anywhere else")
                .containsEntry("com.example.SlowTest", 1_200L);

        IdleHousekeeping.dropHeapResidue(true);
        assertThat(TestClassWalls.get("/w/idle-test", TaskNames.RUN_TESTS))
                .as("with history on the finish path already harvested them")
                .isEmpty();
    }

    /**
     * Back-to-back jobs in different workspaces never reach the settled trim, so entering a new
     * workspace drops what the memos hold for the one left — unless another plan is still live
     * in it.
     */
    @Test
    void entering_another_workspace_drops_the_memos_of_the_one_left_unless_another_plan_is_live(@TempDir Path roots)
            throws Exception {
        MemoTrim.drop(null);
        Path left = roots.resolve("left");
        Path entered = roots.resolve("entered");
        JkBuildParser.parseLocal(manifest(left));
        JkBuildParser.parseLocal(manifest(entered));
        List<String> log = new CopyOnWriteArrayList<>();
        AtomicInteger plans = new AtomicInteger(2);
        IdleHousekeeping housekeeping = housekeeping(plans, roots, log);

        housekeeping.switchedWorkspace(left, entered);
        assertThat(log).as("another plan may still read them").isEmpty();

        plans.set(1);
        housekeeping.switchedWorkspace(left, entered);
        assertThat(log)
                .singleElement(as(STRING))
                .matches("jk engine: workspace switch .*left to .*entered: heap \\d+ -> \\d+ MiB;"
                        + " memos dropped: manifests 2, .*; kept for .*entered");
        assertThat(MemoTrim.drop(null))
                .as("only the entered workspace's manifest is still memoized, as a parse and a document")
                .startsWith("memos dropped: manifests 2,");
    }

    private static Path manifest(Path module) throws IOException {
        Files.createDirectories(module);
        Path toml = module.resolve("jk.toml");
        Files.writeString(toml, "name = \"" + module.getFileName() + "\"\nversion = \"1.0\"\n");
        Files.setLastModifiedTime(toml, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        return toml;
    }

    private static IdleHousekeeping housekeeping(AtomicInteger plans, Path roots, List<String> log) {
        return new IdleHousekeeping(
                plans,
                new ReentrantReadWriteLock(),
                new JkHistoryConfig(false, 30, 512),
                new BuildJournal(roots.resolve("builds")),
                new DecisionQueue(),
                () -> roots.resolve("metrics.jsonl"),
                () -> BuildMetrics.Limits.DEFAULTS,
                roots.resolve("engine"),
                System::currentTimeMillis,
                log::add,
                () -> false,
                () -> false,
                () -> {});
    }

    @Test
    void a_draining_engine_leaves_the_queued_prune_and_old_dumps_to_its_successor(
            @TempDir Path home, @TempDir Path roots) throws Exception {
        System.setProperty("jk.env.JK_HOME", home.toAbsolutePath().toString());
        System.setProperty("jk.env.JK_AUTO_PRUNE", "true");
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
                new DecisionQueue(),
                () -> roots.resolve("metrics.jsonl"),
                () -> BuildMetrics.Limits.DEFAULTS,
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
    }

    @Test
    void the_journal_is_pruned_at_most_hourly_however_many_boundaries_pass(@TempDir Path roots) {
        BuildJournal journal = new BuildJournal(roots.resolve("builds"));
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        List<String> log = new CopyOnWriteArrayList<>();
        IdleHousekeeping housekeeping = new IdleHousekeeping(
                new AtomicInteger(0),
                new ReentrantReadWriteLock(),
                new JkHistoryConfig(true, 30, 512),
                journal,
                new DecisionQueue(),
                () -> roots.resolve("metrics.jsonl"),
                () -> BuildMetrics.Limits.DEFAULTS,
                roots.resolve("engine"),
                now::get,
                log::add,
                () -> false,
                () -> false,
                () -> {});
        journal.append(oldRun("/a"), BuildJournal.Snapshot.NONE);
        housekeeping.pruneJournal();
        assertThat(journal.list()).as("the first boundary prunes").isEmpty();

        journal.append(oldRun("/b"), BuildJournal.Snapshot.NONE);
        now.addAndGet(60_000L);
        housekeeping.pruneJournal();
        assertThat(journal.list())
                .as("a minute later the next boundary does not")
                .hasSize(1);

        now.addAndGet(IdleHousekeeping.JOURNAL_PRUNE_EVERY_MILLIS);
        housekeeping.pruneJournal();
        assertThat(journal.list()).as("an hour on it does").isEmpty();
    }

    private static BuildRecord oldRun(String dir) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                dir,
                "g:a",
                null,
                1_600_000_000_000L,
                1_600_000_000_100L,
                100,
                true,
                false,
                0,
                "9.9-test",
                null,
                List.of(),
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L,
                null,
                List.of());
    }
}
