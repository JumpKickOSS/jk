// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectStore;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.RunMeasures;
import cc.jumpkick.wire.runtime.TestSuiteScaling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The measures a finished run renders for its project and host stores to fold. */
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
     * Only what the estimates read is rendered: step and invocation walls. A step's stage, its
     * queue wait and a module's whole wall are not.
     */
    @Test
    void a_run_renders_step_and_invocation_walls_only() {
        var tasks = List.of(
                new BuildRecord.Task("compile-java", "compile", "SUCCESS", 2000, 1700L),
                new BuildRecord.Task("run-tests", "test", "SUCCESS", 4000, 0L));
        String toml =
                RunMetricsToml.render(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), new RunMeasures());
        assertThat(toml)
                .contains("workspace.wall-ms = 100")
                .contains("invocation.build.wall-ms = 100")
                .contains("task.compile-java.wall-ms = 2000")
                .contains("task.run-tests.wall-ms = 4000")
                .doesNotContain("wait-ms")
                .doesNotContain("phase.");
        assertThat(ProjectStore.scalars(toml))
                .allSatisfy((k, v) -> assertThat(ProjectStore.isKept(k)).isTrue());
    }

    /** A cancelled run renders nothing. */
    @Test
    void a_cancelled_run_renders_nothing() {
        BuildRecord base = record(1_700_000_000_000L, true, "g:a");
        assertThat(RunMetricsToml.render(withCancelled(base), new RunMeasures()))
                .isNull();
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
        Path mod = dir.resolve("server/engine");
        RunMeasures measures = new RunMeasures();
        measures.runners(mod.toString(), 8);
        var modules = List.of(new BuildRecord.Module(
                "g:engine",
                mod.toString(),
                true,
                0,
                20_000,
                List.of(new BuildRecord.Task(TaskNames.RUN_TESTS, "test", "SUCCESS", 17_384, 0L))));
        String toml = RunMetricsToml.render(withModules(record(1_700_000_000_000L, true, "g:a"), modules), measures);

        assertThat(toml).contains(".task.run-tests.wall-ms = 17384");
        assertThat(toml)
                .as("17384 x 8^(1/3), the single-runner-equivalent cost")
                .contains(".task.run-tests.wall1-ms = " + TestSuiteScaling.normalize(17_384, 8));
    }

    private String metricsOf(BuildJournal j, Path mod, RunMeasures.Suite suite, long wall) throws Exception {
        RunMeasures measures = new RunMeasures();
        measures.suite(mod.toString(), suite);
        measures.runners(mod.toString(), 1);
        measures.classWalls(mod.toString(), suite.step(), Map.of("a.SlowIT", 9_000L));
        var modules = List.of(new BuildRecord.Module(
                "g:cli",
                mod.toString(),
                true,
                0,
                wall,
                List.of(new BuildRecord.Task(TaskNames.RUN_TESTS, "test", "SUCCESS", wall, 0L))));
        return requireNonNull(
                RunMetricsToml.render(withModules(record(1_700_000_000_000L, true, "g:a"), modules), measures));
    }

    /**
     * A suite's wall is kept under the selection that produced it. The integration tier of a module
     * takes many times what its default tier does; one row for both priced every tier off a blend.
     */
    @Test
    void an_integration_suite_wall_is_journalled_under_its_own_step() throws Exception {
        Path mod = dir.resolve("clients/cli");
        String toml =
                metricsOf(new BuildJournal(dir), mod, new RunMeasures.Suite("run-tests@integration", false), 575_000);

        assertThat(toml)
                .contains(".task.run-tests@integration.wall-ms = 575000")
                .contains(".task.run-tests@integration.wall1-ms = 575000")
                .doesNotContain(".task.run-tests.wall-ms")
                .as("the class walls sit under the tier's key, not the default tier's")
                .containsPattern("\\[test-class\\.\"[^\"]*clients/cli@integration\"\\.\"a\"]");
    }

    /** A failed run trains no wall, but its suite's classes ran for real and their walls stay. */
    @Test
    void a_failed_run_keeps_its_class_walls_and_nothing_else() throws Exception {
        Path mod = dir.resolve("server/engine");
        RunMeasures measures = new RunMeasures();
        measures.suite(mod.toString(), new RunMeasures.Suite("run-tests@all_integration", false));
        measures.classWalls(mod.toString(), "run-tests@all_integration", Map.of("a.SlowIT", 9_000L));
        var modules = List.of(new BuildRecord.Module(
                "g:engine",
                mod.toString(),
                false,
                0,
                250_000,
                List.of(new BuildRecord.Task(TaskNames.RUN_TESTS, "test", "FAILED", 249_000, 0L))));
        String toml = RunMetricsToml.render(withModules(record(1_700_000_000_000L, false, "g:a"), modules), measures);

        assertThat(toml)
                .containsPattern("\\[test-class\\.\"[^\"]*server/engine@all_integration\"\\.\"a\"]")
                .doesNotContain("wall-ms");
    }

    @Test
    void a_default_suite_keeps_the_bare_row() throws Exception {
        Path mod = dir.resolve("clients/cli");
        String toml = metricsOf(new BuildJournal(dir), mod, new RunMeasures.Suite(TaskNames.RUN_TESTS, false), 54_000);

        assertThat(toml).contains(".task.run-tests.wall-ms = 54000").doesNotContain("run-tests@");
        assertThat(toml).containsPattern("\\[test-class\\.\"[^\"@]*clients/cli\"\\.\"a\"]");
    }

    /** A --class run times the classes it ran, not the suite: no suite wall, but the class walls stay. */
    @Test
    void a_class_filtered_run_leaves_no_suite_wall_but_keeps_its_class_walls() throws Exception {
        Path mod = dir.resolve("clients/cli");
        String toml = metricsOf(new BuildJournal(dir), mod, new RunMeasures.Suite(TaskNames.RUN_TESTS, true), 56_000);

        assertThat(toml)
                .doesNotContain("run-tests.wall-ms")
                .doesNotContain("run-tests.wall1-ms")
                .contains("SlowIT = 9000");
    }

    /**
     * Module keys are spelled relative to the run's project root, so three worktrees of one
     * project write the same keys and their project store folds them into one row set.
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
            requireNonNull(j.append(rec, BuildJournal.Snapshot.NONE));
            String toml = RunMetricsToml.render(rec, new RunMeasures());
            assertThat(toml).contains("module.server/io.task.compile-java.wall-ms = 800");
            assertThat(toml).contains("module." + ModuleKeys.ROOT + ".task.guard.wall-ms = 70");
            assertThat(toml).doesNotContain(root.toString());
        }
        Path home = ProjectBuilds.projectHome(dir, null, dir.resolve("wt").resolve("a"));
        String store = Files.readString(ProjectStore.file(home));
        assertThat(store.lines().filter(l -> l.startsWith("module.server/io.task.compile-java.wall-ms")))
                .as("one row, three samples")
                .containsExactly("module.server/io.task.compile-java.wall-ms 800 800 3 3");
        assertThat(Files.readString(dir.resolve(HostStore.FILE)))
                .as("the host learns the step kind, not the module")
                .contains("task.compile-java.wall-ms 800 800 3")
                .doesNotContain("module.");
    }

    private static BuildRecord withCancelled(BuildRecord base) {
        return new BuildRecord(
                base.id(),
                base.buildNumber(),
                BuildRecord.SCHEMA,
                base.kind(),
                base.dir(),
                base.coord(),
                base.projectId(),
                base.startedAt(),
                base.finishedAt(),
                base.millis(),
                base.success(),
                true,
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
        Path mod = dir.resolve("shared/host");
        var modules = List.of(new BuildRecord.Module(
                "g:host",
                mod.toString(),
                true,
                0,
                2_000,
                List.of(new BuildRecord.Task(TaskNames.RUN_TESTS, "test", "SUCCESS", 1_500, 0L))));
        String toml =
                RunMetricsToml.render(withModules(record(1_700_000_000_000L, true, "g:a"), modules), new RunMeasures());

        assertThat(toml).contains(".task.run-tests.wall-ms = 1500");
        assertThat(toml).doesNotContain(".task.run-tests.wall1-ms");
    }

    /** {@code base} with its task list replaced — the record is wide and all-positional. */
    private static BuildRecord withTasks(BuildRecord base, List<BuildRecord.Task> tasks) {
        return withTasks(base, tasks, base.requestId());
    }

    private static BuildRecord withTasks(BuildRecord base, List<BuildRecord.Task> tasks, long requestId) {
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
                requestId,
                null,
                List.of());
    }

    /**
     * A build that started without a warm compiler worker records no compile walls: they carry the
     * worker's warm-up, and the ETA would price every later warm build from them.
     */
    @Test
    void a_cold_worker_runs_compile_walls_are_not_recorded() throws Exception {
        var tasks = List.of(
                new BuildRecord.Task("compile-java", "compile", "SUCCESS", 2000, 0L),
                new BuildRecord.Task("package-jar", "package", "SUCCESS", 300, 0L));
        RunMeasures measures = new RunMeasures();
        measures.startedWorker();
        String toml = RunMetricsToml.render(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks, 4242L), measures);
        assertThat(toml).contains("task.package-jar.wall-ms = 300");
        assertThat(toml).doesNotContain("task.compile-java.wall-ms");
    }

    /** A compile of part of its sources is recorded apart: the ETA prices the next one from it. */
    @Test
    void a_partial_compile_is_recorded_under_its_own_name() throws Exception {
        var tasks = List.of(
                new BuildRecord.Task("compile-java", "compile", "SUCCESS", 400, 0L, TaskNames.compiledLabel(1, 12)),
                new BuildRecord.Task("compile-test", "compile", "SUCCESS", 900, 0L, TaskNames.compiledLabel(5, 5)));
        String toml =
                RunMetricsToml.render(withTasks(record(1_700_000_000_000L, true, "g:a"), tasks), new RunMeasures());
        assertThat(toml).contains("task.compile-java-incremental.wall-ms = 400");
        assertThat(toml).doesNotContain("task.compile-java.wall-ms");
        assertThat(toml).contains("task.compile-test.wall-ms = 900");
        assertThat(TaskNames.compiledPart(TaskNames.compiledLabel(12, 12))).isFalse();
        assertThat(TaskNames.compiledPart("cache hit 1234abcd")).isFalse();
    }
}
