// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.TestSuiteRunners;
import cc.jumpkick.wire.runtime.TestSuiteScaling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                null /* projectId */,
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
                null,
                false,
                null,
                0L,
                null,
                List.of());
    }

    /**
     * Build numbers restart at 1 in every project home, so two projects on one home both own a
     * {@code runs/1}. The directory a record resolves to is the one under its own project: the
     * results file of one project must never point its transcript at the other's run.
     */
    @Test
    void a_numbered_locator_resolves_under_the_records_own_project_home() {
        BuildJournal j = new BuildJournal(dir);
        BuildRecord a = recordIn("/proj-a", "g:a").withBuildNumber(1);
        BuildRecord b = recordIn("/proj-b", "g:b").withBuildNumber(1);
        assertThat(j.append(a, BuildJournal.Snapshot.NONE)).isEqualTo("1");
        assertThat(j.append(b, BuildJournal.Snapshot.NONE)).isEqualTo("1");
        Path homeA = ProjectBuilds.projectHome(dir, "g:a", Path.of("/proj-a"));
        Path homeB = ProjectBuilds.projectHome(dir, "g:b", Path.of("/proj-b"));
        assertThat(homeA).isNotEqualTo(homeB);

        assertThat(j.runDir("1", a)).contains(homeA.resolve("runs").resolve("1"));
        assertThat(j.runDir("1", b)).contains(homeB.resolve("runs").resolve("1"));
        assertThat(j.detailsFile("g:a", "/proj-a", 1)).contains(homeA.resolve("runs/1/details.jsonl"));
        assertThat(j.detailsFile("g:b", "/proj-b", 1)).contains(homeB.resolve("runs/1/details.jsonl"));
    }

    private static BuildRecord recordIn(String projectDir, String coord) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                projectDir,
                coord,
                null,
                1_700_000_000_900L,
                1_700_000_001_000L,
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

    /** The journal writes {@code trigger}, never a {@code synthetic} key: both loaders derive it. */
    @Test
    void fixture_runs_are_hidden_from_the_raw_path_exactly_as_from_the_parsed_one() {
        BuildJournal j = new BuildJournal(dir);
        j.append(record(1_700_000_001_000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        j.append(
                withTrigger(record(1_700_000_002_000L, true, "g:a"), "calibrate"),
                new BuildJournal.Snapshot(null, null, null));
        j.append(
                withTrigger(record(1_700_000_003_000L, true, "g:a"), "synthetic"),
                new BuildJournal.Snapshot(null, null, null));

        assertThat(j.list()).hasSize(1);
        List<String> raw = j.rawRecords(9);
        assertThat(raw).hasSize(1);
        assertThat(raw.getFirst()).doesNotContain("calibrate").doesNotContain("synthetic");
        assertThat(BuildJournal.scanString(raw.getFirst(), "trigger")).isEqualTo("cli");
    }

    /**
     * The dashboard addresses a run by its record id, which is not the directory name. Once the
     * journal has listed its runs, resolving one of those ids is a single record read — not a
     * parse of every record until the id happens to match.
     */
    @Test
    void a_record_id_resolves_with_one_record_read_once_the_journal_has_been_listed() {
        BuildJournal j = new BuildJournal(dir);
        for (int i = 1; i <= 5; i++) {
            j.append(record(1_700_000_000_000L + i * 1_000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        }
        String id = requireNonNull(j.list().get(2).id());

        long before = j.recordReads();
        assertThat(j.get(id)).isPresent().get().extracting(BuildRecord::id).isEqualTo(id);
        assertThat(j.recordReads() - before).as("get by id").isEqualTo(1);

        before = j.recordReads();
        assertThat(j.recordFile(id)).isPresent();
        assertThat(j.recordReads() - before).as("record file by id").isEqualTo(1);
    }

    /** A journal opened cold still resolves an id it has never seen, and remembers where it was. */
    @Test
    void a_cold_id_lookup_scans_once_and_the_next_lookup_is_a_single_read() {
        BuildJournal writer = new BuildJournal(dir);
        for (int i = 1; i <= 4; i++) {
            writer.append(
                    record(1_700_000_000_000L + i * 1_000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        }
        String id = requireNonNull(writer.list().get(3).id());

        BuildJournal cold = new BuildJournal(dir);
        assertThat(cold.get(id)).isPresent();
        long before = cold.recordReads();
        assertThat(cold.get(id)).isPresent();
        assertThat(cold.recordReads() - before).isEqualTo(1);
    }

    @Test
    void a_deleted_run_no_longer_resolves_by_id() {
        BuildJournal j = new BuildJournal(dir);
        j.append(record(1_700_000_001_000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        j.append(record(1_700_000_002_000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        String id = requireNonNull(j.list().get(0).id());

        assertThat(j.delete(id)).isTrue();
        assertThat(j.get(id)).isEmpty();
        assertThat(j.list()).hasSize(1);
    }

    private static BuildRecord withTrigger(BuildRecord base, String trigger) {
        return new BuildRecord(
                base.id(),
                base.buildNumber(),
                BuildRecord.SCHEMA,
                base.kind(),
                base.dir(),
                base.coord(),
                null /* projectId */,
                base.startedAt(),
                base.finishedAt(),
                base.millis(),
                base.success(),
                base.cancelled(),
                base.exitCode(),
                base.jkVersion(),
                base.tests(),
                base.modules(),
                base.steps(),
                base.diagnostics(),
                trigger,
                base.session(),
                base.commit(),
                base.benefit(),
                base.running(),
                base.io(),
                base.requestId(),
                null,
                List.of());
    }

    @Test
    void format_job_is_journaled_without_a_build_number() {
        BuildJournal j = new BuildJournal(dir);
        BuildRecord run =
                BuildRecord.running(0, "format", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "web", null, 9L);
        String locator = requireNonNull(j.begin(run));
        assertThat(locator).startsWith("j-");
        assertThat(locator).contains("9");
        assertThat(j.get(locator)).isPresent();
        assertThat(j.get(locator).orElseThrow().kind()).isEqualTo("format");
        assertThat(j.get(locator).orElseThrow().buildNumber()).isZero();
        assertThat(j.get(locator).orElseThrow().requestId()).isEqualTo(9L);
        BuildRecord done = new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "format",
                "/proj",
                "g:a",
                null,
                1_700_000_000_000L,
                1_700_000_000_080L,
                80,
                true,
                false,
                0,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(),
                "web",
                null,
                null,
                null,
                false,
                null,
                9L,
                null,
                List.of());
        assertThat(j.complete(locator, done, BuildJournal.Snapshot.NONE)).isTrue();
        assertThat(j.get(locator).orElseThrow().running()).isFalse();
        assertThat(j.get(locator).orElseThrow().success()).isTrue();
        assertThat(j.get(locator).orElseThrow().requestId()).isEqualTo(9L);
        // A later build still gets #1.
        String buildLoc = j.append(record(1_700_000_000_200L, true, "g:a"), BuildJournal.Snapshot.NONE);
        assertThat(buildLoc).isEqualTo("1");
    }

    @Test
    void raw_finished_record_by_request_id_skips_running_stub_then_returns_finished_json() {
        BuildJournal j = new BuildJournal(dir);
        BuildRecord run =
                BuildRecord.running(0, "format", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "web", null, 9L);
        String locator = requireNonNull(j.begin(run));
        assertThat(j.rawFinishedRecordByRequestId(9L)).isEmpty(); // running stub is not a result
        assertThat(j.rawFinishedRecordByRequestId(7L)).isEmpty(); // unknown jid
        BuildRecord done = new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "format",
                "/proj",
                "g:a",
                null,
                1_700_000_000_000L,
                1_700_000_000_080L,
                80,
                true,
                false,
                0,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(),
                "web",
                null,
                null,
                null,
                false,
                null,
                9L,
                null,
                List.of());
        assertThat(j.complete(locator, done, BuildJournal.Snapshot.NONE)).isTrue();
        String json = j.rawFinishedRecordByRequestId(9L).orElseThrow();
        BuildRecord parsed = Json.read(json);
        assertThat(parsed.requestId()).isEqualTo(9L);
        assertThat(parsed.running()).isFalse();
        assertThat(parsed.success()).isTrue();
        // Memoized dir: a second call answers from the same single record file.
        assertThat(j.rawFinishedRecordByRequestId(9L)).isPresent();
    }

    @Test
    void begin_then_complete_keeps_id_and_clears_running() {
        BuildJournal j = new BuildJournal(dir);
        BuildRecord run = BuildRecord.running(27, "build", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "cli");
        String locator = requireNonNull(j.begin(run));
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
     * A row left {@code running} by an engine that died is closed out as a failure the user cannot
     * act on, NOT as a cancellation. Until it was stamped {@code cancelled=true} with exit
     * 130 — {@code 128 + SIGINT} — so `jk history` reported a crashed machine as "the user pressed
     * Ctrl-C". 70 is {@code Exit.SOFTWARE}, spelled here as the literal a reader of the record sees.
     */
    @Test
    void two_non_build_rows_in_one_millisecond_abandon_independently() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        long started = 1_700_000_000_000L;
        String lock = requireNonNull(
                j.begin(BuildRecord.running(0, "lock", "/proj", "g:a", null, started, "9.9", "cli", null, 11L)));
        String format = requireNonNull(
                j.begin(BuildRecord.running(0, "format", "/proj", "g:a", null, started, "9.9", "cli", null, 22L)));
        assertThat(lock).isNotEqualTo(format);
        assertThat(j.get(lock).orElseThrow().startedAt())
                .isEqualTo(j.get(format).orElseThrow().startedAt());

        assertThat(j.abandonStaleRunning("9.9", owner -> false).abandoned()).isEqualTo(2);
        assertThat(j.get(lock).orElseThrow().running()).isFalse();
        assertThat(j.get(format).orElseThrow().running()).isFalse();

        String lockRecord = Files.readString(j.runDir(lock).orElseThrow().resolve("record.json"));
        String formatRecord = Files.readString(j.runDir(format).orElseThrow().resolve("record.json"));
        assertThat(lockRecord).contains("\"kind\": \"lock\"").doesNotContain("\"kind\": \"format\"");
        assertThat(formatRecord).contains("\"kind\": \"format\"").doesNotContain("\"kind\": \"lock\"");
        assertThat(lockRecord).contains("\"requestId\": 11").doesNotContain("\"requestId\": 22");
        assertThat(formatRecord).contains("\"requestId\": 22").doesNotContain("\"requestId\": 11");
    }

    /** A lock or format row has no build number; the sweep still finds its directory by record id. */
    @Test
    void a_running_row_without_a_build_number_is_abandoned_with_its_engine() {
        BuildJournal j = new BuildJournal(dir);
        String locator = requireNonNull(
                j.begin(BuildRecord.running(0, "lock", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "cli")));
        assertThat(j.get(locator).orElseThrow().running()).isTrue();

        assertThat(j.abandonStaleRunning("9.9", owner -> false).abandoned()).isEqualTo(1);
        assertThat(j.get(locator).orElseThrow().running()).isFalse();
    }

    @Test
    void an_abandoned_run_is_a_software_failure_not_a_user_cancel() {
        BuildJournal j = new BuildJournal(dir);
        String locator = requireNonNull(
                j.begin(BuildRecord.running(31, "build", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "cli")));
        assertThat(j.get(locator).orElseThrow().running()).isTrue();

        assertThat(j.abandonStaleRunning("9.9", owner -> false).abandoned()).isEqualTo(1);

        BuildRecord abandoned = j.get(locator).orElseThrow();
        assertThat(abandoned.running()).isFalse();
        assertThat(abandoned.success()).isFalse();
        assertThat(abandoned.cancelled()).isFalse();
        assertThat(abandoned.exitCode()).isEqualTo(70);
        // Nothing else claims it, so a second sweep is a no-op.
        assertThat(j.abandonStaleRunning("9.9", owner -> false).abandoned()).isZero();
    }

    /**
     * A successor's sweep tells a dead engine's row from a draining predecessor's by the owner
     * sidecar the admission wrote: the pid and start instant of the engine that holds the row.
     * This JVM is that engine here, so the row is live to the real liveness check and abandoned
     * only when the owner is judged gone.
     */
    @Test
    void a_running_row_whose_engine_is_alive_is_left_to_it() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        String locator = requireNonNull(
                j.begin(BuildRecord.running(32, "build", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "cli")));
        Path sidecar = j.runDir(locator).orElseThrow().resolve(BuildJournal.ENGINE_OWNER);
        assertThat(Files.readString(sidecar).trim())
                .startsWith(ProcessHandle.current().pid() + " ");

        BuildJournal.StaleSweep sweep = j.abandonStaleRunning("9.9");
        assertThat(sweep.live()).isEqualTo(1);
        assertThat(sweep.abandoned()).isZero();
        assertThat(j.get(locator).orElseThrow().running())
                .as("still the live engine's")
                .isTrue();

        assertThat(j.abandonStaleRunning("9.9", owner -> false).abandoned()).isEqualTo(1);
        assertThat(j.get(locator).orElseThrow().running()).isFalse();
        assertThat(Files.exists(sidecar)).as("a finished row has no owner").isFalse();
    }

    @Test
    void a_running_row_without_an_owner_or_with_a_dead_one_is_abandoned() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        String orphan = requireNonNull(
                j.begin(BuildRecord.running(33, "build", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "cli")));
        Files.delete(j.runDir(orphan).orElseThrow().resolve(BuildJournal.ENGINE_OWNER));
        String dead = requireNonNull(
                j.begin(BuildRecord.running(34, "build", "/proj", "g:a", null, 1_700_000_000_000L, "9.9", "cli")));
        // No process ever carries a pid this large; a pid nothing runs under is a dead engine.
        Files.writeString(j.runDir(dead).orElseThrow().resolve(BuildJournal.ENGINE_OWNER), (1L << 40) + " 0\n");
        assertThat(new EngineOwner(1L << 40, 0L).alive()).isFalse();
        assertThat(EngineOwner.current().alive()).isTrue();

        BuildJournal.StaleSweep sweep = j.abandonStaleRunning("9.9");
        assertThat(sweep.abandoned()).isEqualTo(2);
        assertThat(sweep.live()).isZero();
        assertThat(j.get(orphan).orElseThrow().running()).isFalse();
        assertThat(j.get(dead).orElseThrow().running()).isFalse();
    }

    /**
     * The rollup buckets by the stage the plan <em>declared</em>, not by re-guessing from the task
     * name. Those disagreed: a plugin source generator reports {@code generate} on the wire and was
     * bucketed {@code compile} here, and every {@code stage(RESOLVE)} task in ScriptPlans landed in
     * {@code other} — so the priors were fed by a different task set than the UI displayed.
     */
    @Test
    void metrics_bucket_by_the_declared_stage_not_the_task_name() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        var tasks = List.of(
                // ofTaskName would call this one `compile`; the plan says `generate`.
                new BuildRecord.Task("plugin-android-res", "generate", "SUCCESS", 700, 0L),
                // ofTaskName has no case for this name at all and would bucket it `other`.
                new BuildRecord.Task("resolve-kotlinc", "resolve", "SUCCESS", 300, 0L));
        String locator = requireNonNull(
                j.append(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), BuildJournal.Snapshot.NONE));
        String toml = Files.readString(j.runDir(locator).orElseThrow().resolve("metrics.toml"));

        assertThat(toml).contains("phase.generate.wall-ms = 700");
        assertThat(toml).contains("phase.resolve.wall-ms = 300");
        assertThat(toml).doesNotContain("phase.other.wall-ms");
    }

    /**
     * Regression: {@code phase.*.wall-ms} is one summed key per run — duplicate
     * per-task keys would be folded as a MEAN by MetricsHarvest, deflating the phase priors.
     */
    @Test
    void metrics_toml_sums_phase_walls_per_run() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        var tasks = List.of(
                new BuildRecord.Task("compile-java", "compile", "SUCCESS", 2000, 0L),
                new BuildRecord.Task("copy-resources", "compile", "SUCCESS", 1000, 0L),
                new BuildRecord.Task("write-stamp", "compile", "SUCCESS", 500, 0L),
                new BuildRecord.Task("run-tests", "test", "SUCCESS", 4000, 0L));
        String locator = requireNonNull(
                j.append(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), BuildJournal.Snapshot.NONE));
        Path metrics = j.runDir(locator).orElseThrow().resolve("metrics.toml");
        String toml = Files.readString(metrics);
        // One key per phase, summed: compile-java + copy-resources + write-stamp.
        assertThat(toml).contains("phase.compile.wall-ms = 3500");
        assertThat(toml).contains("phase.test.wall-ms = 4000");
        assertThat(toml.lines()
                        .filter(l -> l.startsWith("phase.compile.wall-ms"))
                        .count())
                .isEqualTo(1);
        // Per-task keys stay per task.
        assertThat(toml).contains("task.compile-java.wall-ms = 2000");
    }

    /**
     * A step that queued behind the shared compiler worker records that wait beside its wall, so a
     * reader summing walls across thirty modules can see how much of the sum was the queue.
     */
    @Test
    void metrics_toml_records_a_steps_queue_wait_beside_its_wall() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        var tasks = List.of(
                new BuildRecord.Task("compile-java", "compile", "SUCCESS", 2000, 1700L),
                new BuildRecord.Task("run-tests", "test", "SUCCESS", 4000, 0L));
        String locator = requireNonNull(
                j.append(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), BuildJournal.Snapshot.NONE));
        String toml = Files.readString(j.runDir(locator).orElseThrow().resolve("metrics.toml"));
        assertThat(toml).contains("task.compile-java.wall-ms = 2000");
        assertThat(toml).contains("task.compile-java.wait-ms = 1700");
        assertThat(toml).contains("task.run-tests.wall-ms = 4000");
        assertThat(toml).doesNotContain("task.run-tests.wait-ms");
    }

    @Test
    void append_then_get_and_list_roundtrip() {
        BuildJournal j = new BuildJournal(dir);
        String locator = requireNonNull(j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE));
        assertThat(locator).isNotNull();
        assertThat(j.get(locator)).isPresent();
        assertThat(j.get(locator).get().success()).isTrue();
        assertThat(j.get(locator).get().coord()).isEqualTo("g:a");
        assertThat(j.list()).hasSize(1);
        // list entry id is timestamp; locator is build-number dir
        assertThat(j.list().get(0).buildNumber()).isGreaterThan(0);
        assertThat(j.get(requireNonNull(j.list().get(0).id()))).isPresent(); // lookup by timestamp id
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
        Path md = dir.resolve("src-jk-results.md");
        Files.writeString(md, "# jk results — OK\nall good");
        Path lock = dir.resolve("src-jk-lock.toml");
        Files.writeString(lock, "version = 1");
        BuildJournal j = new BuildJournal(dir);
        String id = requireNonNull(
                j.append(record(1_700_000_000_000L, true, "g:a"), new BuildJournal.Snapshot(md, lock, "boom\n")));
        assertThat(j.artifact(id, BuildJournal.RESULTS_MD)).isPresent();
        assertThat(j.artifact(id, "jk-lock.toml")).isPresent();
        assertThat(j.artifact(id, BuildJournal.DIAGNOSTICS_TXT)).isPresent();
        assertThat(Files.readString(j.artifact(id, BuildJournal.RESULTS_MD).get()))
                .contains("all good");
        Path details = j.runDir(id).orElseThrow().resolve(ProjectBuilds.DETAILS);
        Files.writeString(details, "{\"type\":\"error\"}\n");
        assertThat(j.artifact(id, ProjectBuilds.DETAILS)).contains(details);
    }

    @Test
    void delete_removes_the_entry() {
        BuildJournal j = new BuildJournal(dir);
        String id = requireNonNull(j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE));
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
        assertThat(j.artifact("..", "jk-lock.toml")).isEmpty();
    }

    @Test
    void concurrent_appends_get_distinct_ids() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        int n = 24;
        CountDownLatch go = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        failures.add(new AssertionError("start latch never opened"));
                        return;
                    }
                    String id = requireNonNull(
                            j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE));
                    if (id != null) ids.add(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    failures.add(e); // otherwise only the id count would show it, without the cause
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            assertThat(t.join(Duration.ofSeconds(30))).isTrue();
        }
        assertThat(failures).isEmpty();
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
     * build numbers are per project, so deleting "8" must not resolve into whichever
     * project home happens to sort first.
     */
    /** an in-flight run's stub must survive a prune that runs alongside it. */
    @Test
    void prune_never_reaps_a_running_entry() {
        BuildJournal j = new BuildJournal(dir);
        // Old enough that any age budget would sweep it, but still running.
        String live = requireNonNull(j.begin(BuildRecord.running(1, "build", "/proj", "g:a", null, 1L, "9.9", "cli")));
        assertThat(live).isNotNull();
        BuildJournal.PruneResult r = j.prune(1, 1, 1_700_000_000_000L);
        assertThat(r.removedEntries()).isZero();
        assertThat(j.get(live)).isPresent();
    }

    /** the limited views must agree with the full list, just truncated. */
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
    void an_epoch_zero_dir_mtime_cannot_push_the_newest_run_out_of_a_limited_cut(@TempDir Path tmp) throws Exception {
        // Newest-N selection cuts by dir mtime BEFORE the finishedAt sort. mtimeOf used to map a
        // stat failure to 0 — the very end of a newest-first list — so the newest run vanished.
        // Epoch-0 is the observable stand-in for a failed stat: the fix over-includes either way.
        BuildJournal j = new BuildJournal(tmp);
        for (int i = 0; i < 4; i++) {
            j.append(record(1_700_000_000_000L + i * 1000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        }
        // Find the run dir holding the newest record and give it a pathological mtime.
        List<Path> dirs;
        try (var walk = Files.walk(tmp)) {
            dirs = walk.filter(p -> p.getFileName().toString().equals("record.json"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(String.valueOf(1_700_000_003_000L));
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(Path::getParent)
                    .toList();
        }
        assertThat(dirs).hasSize(1);
        Files.setLastModifiedTime(dirs.getFirst(), FileTime.fromMillis(0));

        // The cut is limit < N: with the pathological stamp mapped to 0 the newest run sorted to
        // the END of the mtime order and fell outside the window. Over-inclusion keeps it in.
        var top = j.list(2).getFirst();
        assertThat(top.finishedAt()).isEqualTo(1_700_000_003_000L);
        assertThat(j.rawRecords(2)).anyMatch(r -> r.contains(String.valueOf(1_700_000_003_000L)));
    }

    @Test
    void complete_stamps_the_run_dir_at_least_as_new_as_finishedAt(@TempDir Path tmp) throws Exception {
        // The newest-N cut assumes dir mtime tracks finishedAt; today that holds by the side
        // effect of complete() renaming files into the dir. Pin it, so a future finalize-path
        // change cannot silently un-stamp it.
        BuildJournal j = new BuildJournal(tmp);
        long before = System.currentTimeMillis();
        j.append(record(before, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        List<Path> dirs;
        try (var walk = Files.walk(tmp)) {
            dirs = walk.filter(p -> p.getFileName().toString().equals("record.json"))
                    .map(Path::getParent)
                    .toList();
        }
        assertThat(dirs).hasSize(1);
        assertThat(Files.getLastModifiedTime(dirs.getFirst()).toMillis())
                .as("a finalized run dir is stamped no older than its record")
                .isGreaterThanOrEqualTo(before - 5_000);
    }

    @Test
    void raw_records_never_run_a_full_parse_and_a_filter_counts_toward_the_limit() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        for (int i = 0; i < 4; i++) {
            j.append(record(1_700_000_000_000L + i * 1000L, true, "g:a"), new BuildJournal.Snapshot(null, null, null));
        }
        // Corrupt one record's DEEP structure while keeping the top-level shape: a full
        // Json.read rejects it, a lexical raw pass serves it. That the row still arrives is the
        // proof the verbatim path builds no record graph.
        List<Path> records;
        try (var walk = Files.walk(dir)) {
            records = walk.filter(p -> p.getFileName().toString().equals("record.json"))
                    .sorted()
                    .toList();
        }
        assertThat(records).hasSize(4);
        Path victim = records.getFirst();
        String json = Files.readString(victim);
        Files.writeString(victim, json.replaceFirst("\\{", "{\"deep\":[{\"broken\":}],"));
        assertThat(j.rawRecords(9)).hasSize(4);

        // The lexical filter counts toward the limit — no N-times over-read to be left with
        // enough survivors.
        List<String> filtered = j.rawRecords(2, r -> !r.contains("\"broken\":"));
        assertThat(filtered).hasSize(2);
        assertThat(filtered).allSatisfy(r -> assertThat(r).doesNotContain("\"broken\":"));

        // A torn write (no closing brace) is skipped, not streamed into a JSON array.
        Files.writeString(victim, json.substring(0, json.length() / 2));
        assertThat(j.rawRecords(9)).hasSize(3);
    }

    @Test
    void scoped_delete_does_not_touch_another_projects_run_of_the_same_number() {
        BuildJournal j = new BuildJournal(dir);
        j.begin(BuildRecord.running(8, "build", "/projA", "g:a", null, 1_700_000_000_000L, "9.9", "cli"));
        j.begin(BuildRecord.running(8, "build", "/projB", "g:b", null, 1_700_000_000_000L, "9.9", "cli"));
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
        j.begin(BuildRecord.running(8, "build", "/projA", "g:a", null, 1_700_000_000_000L, "9.9", "cli"));
        assertThat(j.delete("8", "g:b", "/projB")).isFalse();
        assertThat(j.runDir("g:a", "/projA", 8)).isPresent();
    }

    /** {@code base} with its module list replaced. Same shape as {@link #withTasks}. */
    private static BuildRecord withModules(BuildRecord base, List<BuildRecord.Module> modules) {
        return new BuildRecord(
                base.id(),
                base.buildNumber(),
                BuildRecord.SCHEMA,
                base.kind(),
                base.dir(),
                base.coord(),
                null /* projectId */,
                base.startedAt(),
                base.finishedAt(),
                base.millis(),
                base.success(),
                base.cancelled(),
                base.exitCode(),
                base.jkVersion(),
                base.tests(),
                modules,
                base.steps(),
                base.diagnostics(),
                base.trigger(),
                base.session(),
                base.commit(),
                base.benefit(),
                base.running(),
                base.io(),
                base.requestId(),
                null,
                List.of());
    }

    /**
     * A suite wall is only re-usable if the record also says how many runners produced it, and the
     * re-usable <em>form</em> is the wall normalized to one runner ({@code TestSuiteScaling}): a
     * mean of raw walls across runs that sharded the suite differently describes no build that ever
     * ran. The forecast reads {@code wall1-ms}, so this is the write side of that contract.
     */
    @Test
    void a_suite_wall_is_journalled_with_its_runner_count_and_normalized() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        Path mod = dir.resolve("server/engine");
        TestSuiteRunners.put(mod.toString(), 8);
        var modules = List.of(new BuildRecord.Module(
                "g:engine",
                mod.toString(),
                true,
                0,
                20_000,
                List.of(new BuildRecord.Task(TaskNames.RUN_TESTS, "test", "SUCCESS", 17_384, 0L))));
        String locator = requireNonNull(
                j.append(withModules(record(1_700_000_000_000L, true, "g:a"), modules), BuildJournal.Snapshot.NONE));
        String toml = Files.readString(j.runDir(locator).orElseThrow().resolve("metrics.toml"));

        assertThat(toml).contains(".task.run-tests.wall-ms = 17384");
        assertThat(toml).contains(".task.run-tests.workers = 8");
        assertThat(toml)
                .as("17384 x 8^(1/3), the single-runner-equivalent cost")
                .contains(".task.run-tests.wall1-ms = " + TestSuiteScaling.normalize(17_384, 8));
    }

    /**
     * Module keys are spelled relative to the run's project root, so three worktrees of one
     * project write the same keys and the harvest folds them into one row set.
     */
    @Test
    void three_worktrees_of_one_project_write_one_row_set() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        for (String name : List.of("a", "b", "c")) {
            Path root = Files.createDirectories(dir.resolve("wt").resolve(name));
            Files.writeString(root.resolve("jk.toml"), "id = \"demo\"\n");
            Path mod = root.resolve("server/io");
            var tasks = List.of(new BuildRecord.Task("guard", "check", "SUCCESS", 70, 0L));
            var modules = List.of(new BuildRecord.Module(
                    "g:io",
                    mod.toString(),
                    true,
                    0,
                    900,
                    List.of(new BuildRecord.Task("compile-java", "compile", "SUCCESS", 800, 0L))));
            BuildRecord base = record(1_700_000_000_000L, true, "g:a");
            BuildRecord rec = withModules(withTasks(withDir(base, root.toString()), tasks), modules);
            String locator = requireNonNull(j.append(rec, BuildJournal.Snapshot.NONE));
            String toml = Files.readString(j.runDir(locator).orElseThrow().resolve("metrics.toml"));
            assertThat(toml).contains("module.server/io.task.compile-java.wall-ms = 800");
            assertThat(toml).contains("module." + ModuleKeys.ROOT + ".task.guard.wall-ms = 70");
            assertThat(toml).doesNotContain(root.toString());
        }
        MetricsHarvest.get().runOnce(dir);
        Path home = ProjectBuilds.projectHome(dir, null, dir.resolve("wt").resolve("a"));
        String ledger = Files.readString(home.resolve(ProjectBuilds.PROJECT_METRICS));
        assertThat(ledger.lines().filter(l -> l.startsWith("module.server/io.task.compile-java.wall-ms")))
                .as("one mean, one last, one count")
                .hasSize(3);
        assertThat(ledger).contains("module.server/io.task.compile-java.wall-ms = 3");
    }

    private static BuildRecord withDir(BuildRecord base, String dir) {
        return new BuildRecord(
                base.id(),
                base.buildNumber(),
                BuildRecord.SCHEMA,
                base.kind(),
                dir,
                base.coord(),
                base.projectId(),
                base.startedAt(),
                base.finishedAt(),
                base.millis(),
                base.success(),
                base.cancelled(),
                base.exitCode(),
                base.jkVersion(),
                base.tests(),
                base.modules(),
                base.steps(),
                base.diagnostics(),
                base.trigger(),
                base.session(),
                base.commit(),
                base.benefit(),
                base.running(),
                base.io(),
                base.requestId(),
                base.publish(),
                base.coverage());
    }

    /** No recorded runner count → no runner keys at all, rather than a guessed 1. */
    @Test
    void a_suite_wall_with_no_recorded_concurrency_writes_no_runner_keys() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        Path mod = dir.resolve("shared/host");
        TestSuiteRunners.take(mod.toString()); // drain anything a sibling test left
        var modules = List.of(new BuildRecord.Module(
                "g:host",
                mod.toString(),
                true,
                0,
                2_000,
                List.of(new BuildRecord.Task(TaskNames.RUN_TESTS, "test", "SUCCESS", 1_500, 0L))));
        String locator = requireNonNull(
                j.append(withModules(record(1_700_000_000_000L, true, "g:a"), modules), BuildJournal.Snapshot.NONE));
        String toml = Files.readString(j.runDir(locator).orElseThrow().resolve("metrics.toml"));

        assertThat(toml).contains(".task.run-tests.wall-ms = 1500");
        assertThat(toml).doesNotContain(".task.run-tests.workers");
        assertThat(toml).doesNotContain(".task.run-tests.wall1-ms");
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
                null /* projectId */,
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
                base.session(),
                base.commit(),
                base.benefit(),
                base.running(),
                base.io(),
                base.requestId(),
                null,
                List.of());
    }
}
