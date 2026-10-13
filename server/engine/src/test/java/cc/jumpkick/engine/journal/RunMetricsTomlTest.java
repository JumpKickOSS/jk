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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code metrics.toml} a finished run writes beside its record. */
class RunMetricsTomlTest {

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
                .as("one mean, one count: a module task row keeps no last copy")
                .hasSize(2);
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
