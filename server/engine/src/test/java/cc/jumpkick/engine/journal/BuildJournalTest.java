// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildJournalTest {

    @TempDir
    Path dir;

    private static BuildRecord record(long finishedAt, boolean success, String coord) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                "/proj",
                coord,
                finishedAt - 100,
                finishedAt,
                100,
                success,
                false,
                success ? 0 : 1,
                "9.9-test",
                null,
                List.of(),
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                false,
                null);
    }

    @Test
    void begin_then_complete_keeps_id_and_clears_running() {
        BuildJournal j = new BuildJournal(dir);
        BuildRecord run = BuildRecord.running(27, "build", "/proj", "g:a", 1_700_000_000_000L, "9.9", "cli");
        String locator = j.begin(run);
        assertThat(locator).isEqualTo("27"); // directory name = build number
        assertThat(j.get(locator)).isPresent();
        assertThat(j.get(locator).orElseThrow().running()).isTrue();
        assertThat(j.get(locator).orElseThrow().buildNumber()).isEqualTo(27);
        // record.id is a UTC timestamp stamp, not the directory name
        assertThat(j.get(locator).orElseThrow().id()).matches("\\d{8}T\\d{9}");
        assertThat(j.detailsFile(locator)).isPresent();
        assertThat(j.runDir("g:a", "/proj", 27)).isPresent();
        BuildRecord done = record(1_700_000_000_100L, true, "g:a").withBuildNumber(27);
        assertThat(j.complete(locator, done, BuildJournal.Snapshot.NONE)).isTrue();
        assertThat(j.get(locator).orElseThrow().running()).isFalse();
        assertThat(j.get(locator).orElseThrow().buildNumber()).isEqualTo(27);
        assertThat(j.get(locator).orElseThrow().success()).isTrue();
        assertThat(j.runDir(locator).map(p -> p.resolve("metrics.toml")).filter(Files::isRegularFile))
                .isPresent();
    }

    /**
     * The rollup buckets by the stage the plan <em>declared</em>, not by re-guessing from the task
     * name. Those disagreed: a plugin source generator reports {@code generate} on the wire and was
     * bucketed {@code compile} here, and every {@code stage(RESOLVE)} task in ScriptPlans landed in
     * {@code other} — so the priors were fed by a different task set than the UI displayed
     * (JK-1610).
     */
    @Test
    void metrics_bucket_by_the_declared_stage_not_the_task_name() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        var tasks = List.of(
                // ofTaskName would call this one `compile`; the plan says `generate`.
                new BuildRecord.Task("plugin-android-res", "generate", "SUCCESS", 700),
                // ofTaskName has no case for this name at all and would bucket it `other`.
                new BuildRecord.Task("resolve-kotlinc", "resolve", "SUCCESS", 300));
        String locator = j.append(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), BuildJournal.Snapshot.NONE);
        String toml = Files.readString(j.runDir(locator).orElseThrow().resolve("metrics.toml"));

        assertThat(toml).contains("phase.generate.wall-ms = 700");
        assertThat(toml).contains("phase.resolve.wall-ms = 300");
        assertThat(toml).doesNotContain("phase.other.wall-ms");
    }

    /**
     * Regression (JK-1587): {@code phase.*.wall-ms} is one summed key per run — duplicate
     * per-task keys would be folded as a MEAN by MetricsHarvest, deflating the phase priors.
     */
    @Test
    void metrics_toml_sums_phase_walls_per_run() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        var tasks = List.of(
                new BuildRecord.Task("compile-java", "compile", "SUCCESS", 2000),
                new BuildRecord.Task("copy-resources", "compile", "SUCCESS", 1000),
                new BuildRecord.Task("write-stamp", "compile", "SUCCESS", 500),
                new BuildRecord.Task("run-tests", "test", "SUCCESS", 4000));
        String locator =
                j.append(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), BuildJournal.Snapshot.NONE);
        Path metrics = j.runDir(locator).orElseThrow().resolve("metrics.toml");
        String toml = Files.readString(metrics);
        // One key per phase, summed: compile-java + copy-resources + write-stamp.
        assertThat(toml).contains("phase.compile.wall-ms = 3500");
        assertThat(toml).contains("phase.test.wall-ms = 4000");
        assertThat(toml.lines().filter(l -> l.startsWith("phase.compile.wall-ms")).count()).isEqualTo(1);
        // Per-task keys stay per task.
        assertThat(toml).contains("task.compile-java.wall-ms = 2000");
    }

    @Test
    void append_then_get_and_list_roundtrip() {
        BuildJournal j = new BuildJournal(dir);
        String locator = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        assertThat(locator).isNotNull();
        assertThat(j.get(locator)).isPresent();
        assertThat(j.get(locator).get().success()).isTrue();
        assertThat(j.get(locator).get().coord()).isEqualTo("g:a");
        assertThat(j.list()).hasSize(1);
        // list entry id is timestamp; locator is build-number dir
        assertThat(j.list().get(0).buildNumber()).isGreaterThan(0);
        assertThat(j.get(j.list().get(0).id())).isPresent(); // lookup by timestamp id
    }

    @Test
    void list_is_newest_first() {
        BuildJournal j = new BuildJournal(dir);
        String older = j.append(record(1_000_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        String newer = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        List<BuildRecord> list = j.list();
        // Locator is build-number dir; list is ordered by finishedAt, newest first.
        assertThat(list.get(0).buildNumber()).isEqualTo(Long.parseLong(newer));
        assertThat(list.get(1).buildNumber()).isEqualTo(Long.parseLong(older));
        assertThat(list.get(0).finishedAt()).isGreaterThan(list.get(1).finishedAt());
    }

    @Test
    void snapshot_files_are_copied() throws Exception {
        Path md = dir.resolve("src-test-results.md");
        Files.writeString(md, "# Test Results\nall good");
        Path lock = dir.resolve("src-jk-lock.toml");
        Files.writeString(lock, "version = 1");
        BuildJournal j = new BuildJournal(dir);
        String id = j.append(record(1_700_000_000_000L, true, "g:a"), new BuildJournal.Snapshot(md, lock, "boom\n"));
        assertThat(j.artifact(id, BuildJournal.TEST_RESULTS_MD)).isPresent();
        assertThat(j.artifact(id, BuildJournal.LOCKFILE)).isPresent();
        assertThat(j.artifact(id, BuildJournal.DIAGNOSTICS_TXT)).isPresent();
        assertThat(Files.readString(j.artifact(id, BuildJournal.TEST_RESULTS_MD).get()))
                .contains("all good");
    }

    @Test
    void delete_removes_the_entry() {
        BuildJournal j = new BuildJournal(dir);
        String id = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        assertThat(j.delete(id)).isTrue();
        assertThat(j.get(id)).isEmpty();
        assertThat(j.list()).isEmpty();
        assertThat(j.delete(id)).isFalse(); // already gone
    }

    @Test
    void hostile_ids_cannot_escape_the_journal_dir() {
        BuildJournal j = new BuildJournal(dir);
        assertThat(j.get("../secret")).isEmpty();
        assertThat(j.delete("../secret")).isFalse();
        assertThat(j.get("a/b")).isEmpty();
        assertThat(j.artifact("..", BuildJournal.LOCKFILE)).isEmpty();
    }

    @Test
    void concurrent_appends_get_distinct_ids() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        int n = 24;
        CountDownLatch go = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    return;
                }
                String id = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
                if (id != null) ids.add(id);
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) t.join();
        assertThat(ids).hasSize(n); // same finished-timestamp, still no collisions
        assertThat(j.list()).hasSize(n);
    }

    @Test
    void prune_drops_entries_past_the_age_limit() {
        BuildJournal j = new BuildJournal(dir);
        long now = 1_700_000_000_000L;
        long day = 86_400_000L;
        j.append(record(now - 10 * day, true, "old"), BuildJournal.Snapshot.NONE);
        String fresh = j.append(record(now - 1, true, "new"), BuildJournal.Snapshot.NONE);
        BuildJournal.PruneResult r = j.prune(2 * day, 0, now);
        assertThat(r.removedEntries()).isEqualTo(1);
        assertThat(j.list()).hasSize(1);
        assertThat(j.list().get(0).buildNumber()).isEqualTo(Long.parseLong(fresh));
    }

    @Test
    void prune_enforces_the_disk_budget_oldest_first() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        long now = 1_700_000_000_000L;
        String big = "x".repeat(4096);
        // Same project so build numbers are 1,2,3 under one home.
        String oldest = j.append(record(now - 3000, true, "g:a"), new BuildJournal.Snapshot(null, null, big));
        j.append(record(now - 2000, true, "g:a"), new BuildJournal.Snapshot(null, null, big));
        String newest = j.append(record(now - 1000, true, "g:a"), new BuildJournal.Snapshot(null, null, big));
        // Budget below the 3-entry total forces at least the oldest out.
        BuildJournal.PruneResult r = j.prune(0, 6000, now);
        assertThat(r.removedEntries()).isGreaterThanOrEqualTo(1);
        List<Long> numbers = j.list().stream().map(BuildRecord::buildNumber).toList();
        assertThat(numbers).contains(Long.parseLong(newest)).doesNotContain(Long.parseLong(oldest));
    }

    /**
     * JK-1471: build numbers are per project, so deleting "8" must not resolve into whichever
     * project home happens to sort first.
     */
    /** JK-1491: an in-flight run's stub must survive a prune that runs alongside it. */
    @Test
    void prune_never_reaps_a_running_entry() {
        BuildJournal j = new BuildJournal(dir);
        // Old enough that any age budget would sweep it, but still running.
        String live = j.begin(BuildRecord.running(1, "build", "/proj", "g:a", 1L, "9.9", "cli"));
        assertThat(live).isNotNull();
        BuildJournal.PruneResult r = j.prune(1, 1, 1_700_000_000_000L);
        assertThat(r.removedEntries()).isZero();
        assertThat(j.get(live)).isPresent();
    }

    /** JK-1479/JK-1481: the limited views must agree with the full list, just truncated. */
    @Test
    void limited_list_and_raw_records_match_the_full_list() {
        BuildJournal j = new BuildJournal(dir);
        for (int i = 0; i < 5; i++) {
            j.append(record(1_700_000_000_000L + i * 1000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        }
        List<BuildRecord> full = j.list();
        assertThat(full).hasSize(5);
        assertThat(j.list(3)).containsExactlyElementsOf(full.subList(0, 3));
        assertThat(j.list(99)).containsExactlyElementsOf(full);
        assertThat(j.list(0)).isEmpty();

        List<String> raw = j.rawRecords(3);
        assertThat(raw).hasSize(3);
        // Same records, in the same order — the raw text is the JSON each was parsed from.
        for (int i = 0; i < raw.size(); i++) {
            assertThat(raw.get(i)).contains(full.get(i).id());
        }
    }

    @Test
    void scoped_delete_does_not_touch_another_projects_run_of_the_same_number() {
        BuildJournal j = new BuildJournal(dir);
        j.begin(BuildRecord.running(8, "build", "/projA", "g:a", 1_700_000_000_000L, "9.9", "cli"));
        j.begin(BuildRecord.running(8, "build", "/projB", "g:b", 1_700_000_000_000L, "9.9", "cli"));
        assertThat(j.runDir("g:a", "/projA", 8)).isPresent();
        assertThat(j.runDir("g:b", "/projB", 8)).isPresent();

        assertThat(j.delete("8", "g:b", "/projB")).isTrue();

        assertThat(j.runDir("g:b", "/projB", 8)).as("target removed").isEmpty();
        assertThat(j.runDir("g:a", "/projA", 8))
                .as("the other project's run of the same number survives")
                .isPresent();
    }

    @Test
    void scoped_delete_of_a_number_absent_from_that_project_is_a_no_op() {
        BuildJournal j = new BuildJournal(dir);
        j.begin(BuildRecord.running(8, "build", "/projA", "g:a", 1_700_000_000_000L, "9.9", "cli"));
        assertThat(j.delete("8", "g:b", "/projB")).isFalse();
        assertThat(j.runDir("g:a", "/projA", 8)).isPresent();
    }

    /** {@code base} with its task list replaced — the record is wide and all-positional. */
    private static BuildRecord withTasks(BuildRecord base, List<BuildRecord.Task> tasks) {
        return new BuildRecord(
                base.id(),
                base.buildNumber(),
                BuildRecord.SCHEMA,
                base.kind(),
                base.dir(),
                base.coord(),
                base.startedAt(),
                base.finishedAt(),
                base.millis(),
                base.success(),
                base.cancelled(),
                base.exitCode(),
                base.jkVersion(),
                base.tests(),
                base.modules(),
                tasks,
                base.diagnostics(),
                base.trigger(),
                base.commit(),
                base.benefit(),
                base.running(),
                base.io());
    }

}
