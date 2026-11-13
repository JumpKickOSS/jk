// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's estimate store: each finished run folds into running means of the measures the
 * estimates read, bounded by the project's current shape, and reads back as the aggregates view.
 */
class ProjectStoreTest {

    private static final int MODULES = 40;
    private static final int CLASSES_PER_MODULE = 35;
    private static final String[] TASKS = {
        "parse-build", "resolve-deps", "compile-java", "copy-resources", "compile-test", "run-tests",
        "package-jar", "guard", "write-stamp", "generate-sources", "compile-kotlin", "javadoc"
    };

    @Test
    void the_first_ten_samples_are_a_plain_mean_and_later_ones_an_exponential_one() {
        RunningMean m = RunningMean.fold(null, 100, 1);
        for (int i = 2; i <= 10; i++) m = RunningMean.fold(m, i * 100, i);
        assertThat(m.mean()).isCloseTo(550, within(1e-9));
        assertThat(m.count()).isEqualTo(10);
        assertThat(m.last()).isEqualTo(1000);
        assertThat(m.seen()).isEqualTo(10);
        RunningMean next = RunningMean.fold(m, 650, 11);
        assertThat(next.mean()).as("a tenth of the way to the sample").isCloseTo(560, within(1e-9));
    }

    @Test
    void a_far_sample_is_clipped_once_the_mean_has_three_samples_but_kept_as_last() {
        RunningMean m = RunningMean.fold(null, 100, 1);
        m = RunningMean.fold(m, 100_000, 2);
        assertThat(m.mean()).as("two samples: no clip yet").isCloseTo(50_050, within(1e-9));
        RunningMean settled = RunningMean.fold(RunningMean.fold(RunningMean.fold(null, 100, 1), 100, 2), 100, 3);
        RunningMean spiked = RunningMean.fold(settled, 100_000, 4);
        assertThat(spiked.mean()).as("clipped to four times the mean").isCloseTo(100 + (400 - 100) / 4.0, within(1e-9));
        assertThat(spiked.last()).isEqualTo(100_000);
        RunningMean dipped = RunningMean.fold(settled, 1, 4);
        assertThat(dipped.mean()).isCloseTo(100 + (25 - 100) / 4.0, within(1e-9));
    }

    @Test
    void a_run_folds_its_kept_keys_once_each_and_drops_the_rest(@TempDir Path home) throws Exception {
        ProjectStore.foldRun(home, """
                workspace.wall-ms = 1000
                invocation.build.wall-ms = 1000
                invocation.build._/d2.wall-ms = 1000
                task.compile-java.wall-ms = 100
                task.compile-java.wall-ms = 300
                task.compile-java.wait-ms = 7
                module.a.task.compile-java.wall-ms = 100
                module.b.task.run-tests.wall1-ms = 900
                module.b.task.run-tests.workers = 4
                module.a.wall-ms = 400
                module.a.phase.compile.wall-ms = 100
                phase.compile.wall-ms = 400
                """);
        Map<String, RunningMean> means = means(home);
        assertThat(means)
                .containsOnlyKeys(
                        "workspace.wall-ms",
                        "invocation.build.wall-ms",
                        "invocation.build._/d2.wall-ms",
                        "task.compile-java.wall-ms",
                        "module.a.task.compile-java.wall-ms",
                        "module.b.task.run-tests.wall1-ms");
        assertThat(means.get("task.compile-java.wall-ms"))
                .as("a key measured twice in one run is one sample: their mean")
                .isEqualTo(new RunningMean(200, 200, 1, 1));
        assertThat(Files.readString(ProjectStore.file(home))).contains("[meta]\nruns 1\n");
    }

    @Test
    void class_walls_fold_per_suite_and_class(@TempDir Path home) throws Exception {
        String run = """
                workspace.wall-ms = 1000

                [test-class."server/io"."com.example"]
                IoTest = 300

                [test-class."server/io@integration"."com.example"]
                IoIT = 900
                """;
        ProjectStore.foldRun(home, run);
        ProjectStore.foldRun(home, run.replace("IoTest = 300", "IoTest = 500"));
        Map<String, RunningMean> walls = new LinkedHashMap<>();
        ProjectStore.scan(home, null, (dir, fqcn, m) -> walls.put(dir + " " + fqcn, m));
        assertThat(walls)
                .containsEntry("server/io com.example.IoTest", new RunningMean(400, 500, 2, 2))
                .containsEntry("server/io@integration com.example.IoIT", new RunningMean(900, 900, 2, 2));
        assertThat(Files.readString(ProjectStore.file(home)))
                .contains("server/io\tcom.example.IoTest\t500\t400\t2\t2\n");
    }

    @Test
    void a_row_no_run_refreshed_in_two_hundred_runs_is_dropped(@TempDir Path home) {
        ProjectStore.foldRun(home, "task.gone.wall-ms = 5\n[test-class.\"m\".\"p\"]\nGone = 5\n");
        for (int i = 0; i < ProjectStore.STALE_RUNS; i++) ProjectStore.foldRun(home, "task.kept.wall-ms = 5\n");
        assertThat(means(home)).containsKeys("task.gone.wall-ms", "task.kept.wall-ms");
        ProjectStore.foldRun(home, "task.kept.wall-ms = 5\n");
        assertThat(means(home)).containsOnlyKeys("task.kept.wall-ms");
        List<String> classes = new ArrayList<>();
        ProjectStore.scan(home, null, (dir, fqcn, m) -> classes.add(fqcn));
        assertThat(classes).isEmpty();
    }

    @Test
    void past_the_caps_the_least_recently_seen_rows_go_first(@TempDir Path home) {
        StringBuilder old = new StringBuilder();
        for (int i = 0; i < 50; i++) old.append("task.old").append(i).append(".wall-ms = 1\n");
        old.append("[test-class.\"old\".\"p\"]\n");
        for (int i = 0; i < 50; i++) old.append("Old").append(i).append(" = 1\n");
        ProjectStore.foldRun(home, old.toString());
        StringBuilder fresh = new StringBuilder();
        for (int i = 0; i < ProjectStore.MAX_MEANS; i++) {
            fresh.append("task.t").append(String.format(Locale.ROOT, "%05d", i)).append(".wall-ms = 1\n");
        }
        fresh.append("[test-class.\"new\".\"p\"]\n");
        for (int i = 0; i < ProjectStore.MAX_CLASSES; i++) {
            fresh.append("C").append(String.format(Locale.ROOT, "%05d", i)).append(" = 1\n");
        }
        ProjectStore.foldRun(home, fresh.toString());

        Map<String, RunningMean> means = means(home);
        assertThat(means).hasSize(ProjectStore.MAX_MEANS).doesNotContainKey("task.old0.wall-ms");
        List<String> suites = new ArrayList<>();
        ProjectStore.scan(home, null, (dir, fqcn, m) -> suites.add(dir));
        assertThat(suites).hasSize(ProjectStore.MAX_CLASSES).containsOnly("new");
    }

    @Test
    void concurrent_folds_each_land(@TempDir Path home) throws Exception {
        int runs = 16;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                int n = i;
                done.add(pool.submit(() -> ProjectStore.foldRun(
                        home, "task.compile-java.wall-ms = 100\ntask.own" + n + ".wall-ms = 1\n", lines -> {
                            List<String> out = new ArrayList<>(lines);
                            out.add("{\"run\":" + n + "}");
                            return out;
                        })));
            }
            for (Future<?> f : done) f.get();
        }
        Map<String, RunningMean> means = means(home);
        assertThat(means)
                .extractingByKey("task.compile-java.wall-ms")
                .extracting(RunningMean::count)
                .isEqualTo((long) runs);
        assertThat(means).hasSize(runs + 1);
        assertThat(ProjectStore.runs(home)).hasSize(runs);
        assertThat(Files.readString(ProjectStore.file(home))).contains("runs " + runs + "\n");
    }

    @Test
    void the_runs_section_survives_a_fold_without_a_ledger_line(@TempDir Path home) {
        ProjectStore.updateRuns(home, lines -> List.of("{\"a\":1}"));
        ProjectStore.foldRun(home, "task.x.wall-ms = 5\n");
        assertThat(ProjectStore.runs(home)).containsExactly("{\"a\":1}");
    }

    @Test
    void the_aggregates_view_expands_module_keys_reads_class_means_and_keeps_no_module_task_last(@TempDir Path builds)
            throws Exception {
        Path home = builds.resolve("projects").resolve("demo");
        for (int run = 1; run <= 3; run++) ProjectStore.foldRun(home, runMetrics(run * 100));
        Path checkout = Files.createDirectories(builds.resolve("wt").resolve("c"));
        Files.writeString(checkout.resolve("jk.toml"), "id = \"demo\"\n");

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        AggregatedMetrics.load(builds, null, checkout); // classes warm
        long before = threads.getCurrentThreadAllocatedBytes();
        AggregatedMetrics agg = AggregatedMetrics.load(builds, null, checkout);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(allocated).as("bytes allocated reading the store").isLessThan(10L << 20);
        String m1 = checkout.resolve("server/m1").toString();
        assertThat(agg.taskWallMs(m1, "run-tests"))
                .as("the mean of 105, 205, 305")
                .hasValue(205);
        assertThat(agg.last(AggregatedMetrics.sanitize("module." + m1 + ".task.run-tests.wall-ms")))
                .as("a module step is read by its mean")
                .isEmpty();
        assertThat(agg.count(AggregatedMetrics.sanitize("module." + m1 + ".task.run-tests.wall-ms")))
                .isEqualTo(3);
        assertThat(agg.last("workspace.wall-ms")).hasValue(9300);
        assertThat(agg.testClassWallMs(m1, "com.example.m1.T0Test")).hasValue(200);
        assertThat(agg.taskWallMs(checkout.toString(), "guard")).hasValue(207);
        assertThat(agg.fresh()).isTrue();
        ProjectStore.foldRun(home, runMetrics(400));
        assertThat(agg.fresh()).isFalse();
        assertThat(Files.size(ProjectStore.file(home)))
                .as("a forty-module, fourteen-hundred-class project store")
                .isLessThan(200 * 1024);
    }

    @Test
    void the_host_folds_its_kinds_and_invocations_and_the_view_reads_them(@TempDir Path builds) {
        Path file = builds.resolve(HostStore.FILE);
        for (int i = 0; i < 3; i++) {
            HostStore.foldMeans(
                    file,
                    ProjectStore.scalars("invocation.build.wall-ms = 4000\ninvocation.build.sub.wall-ms = 4000\n"
                            + "module.a.task.compile-java.wall-ms = 5\ntask.compile-java.wall-ms = 5\n"));
        }
        AggregatedMetrics host = AggregatedMetrics.hostOnly(builds);
        assertThat(host.count("invocation.build.wall-ms")).isEqualTo(3);
        assertThat(host.mean("invocation.build.wall-ms")).hasValue(4000.0);
        assertThat(host.mean("task.compile-java.wall-ms")).hasValue(5.0);
        assertThat(host.hostMeanMap()).doesNotContainKeys("invocation.build.sub.wall-ms");
        assertThat(host.hostMeanMap().keySet()).noneMatch(k -> k.startsWith("module."));
        assertThat(host.meanMap()).isEmpty();
    }

    private static Map<String, RunningMean> means(Path home) {
        Map<String, RunningMean> out = new LinkedHashMap<>();
        ProjectStore.scan(home, out::put, null);
        return out;
    }

    /** What the journal renders for one run of a checkout: root tasks, module tasks, class walls. */
    private static String runMetrics(int base) {
        StringBuilder sb = new StringBuilder("# run metrics\nworkspace.wall-ms = ")
                .append(9000 + base)
                .append('\n');
        sb.append("invocation.build.wall-ms = 9000\n");
        sb.append("module._.task.guard.wall-ms = ").append(base + 7).append('\n');
        for (int m = 1; m <= MODULES; m++) {
            for (int t = 0; t < TASKS.length; t++) {
                sb.append("module.server/m")
                        .append(m)
                        .append(".task.")
                        .append(TASKS[t])
                        .append(".wall-ms = ")
                        .append(base + t)
                        .append('\n');
            }
        }
        for (int m = 1; m <= MODULES; m++) {
            Map<String, String> walls = new LinkedHashMap<>();
            for (int c = 0; c < CLASSES_PER_MODULE; c++) walls.put("com.example.m" + m + ".T" + c + "Test", "" + base);
            MetricsFile.appendClassWalls(sb, "server/m" + m, walls);
        }
        return sb.toString();
    }
}
