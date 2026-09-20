// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsHarvestTest {

    @Test
    void trimmedMean_drops_outliers_when_enough_samples() {
        List<Double> s = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0);
        double m = MetricsHarvest.trimmedMean(s);
        assertThat(m).isLessThan(20).isGreaterThan(3);
    }

    @Test
    void harvest_writes_project_and_host_metrics(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        Files.writeString(run.metricsFile(), """
                workspace.wall-ms = 1000
                task.compile-java.wall-ms = 200
                host.run-tests-per-method-ms = 12
                """);
        MetricsHarvest.get().configure(50, 90);
        MetricsHarvest.get().runOnce(root);

        Path projectMetrics = run.projectHome().resolve(ProjectBuilds.PROJECT_METRICS);
        assertThat(Files.isRegularFile(projectMetrics)).isTrue();
        String pm = Files.readString(projectMetrics);
        assertThat(pm)
                .contains("[mean]")
                .contains("workspace.wall-ms")
                .contains("[last]")
                .contains("[count]");

        Path host = ProjectBuilds.hostMetricsFile(root);
        assertThat(Files.isRegularFile(host)).isTrue();
        String hm = Files.readString(host);
        assertThat(hm).contains("host.run-tests-per-method-ms").contains("task.compile-java.wall-ms");
        assertThat(hm).doesNotContain("workspace.wall-ms");
    }

    /**
     * A wall measured while another run shared the machine is a contended sample. The row's mean,
     * last and count come from the runs that ran alone while there are any; a row only ever seen
     * under contention keeps what it has; the overlap is written beside the run.
     */
    @Test
    void contended_samples_stay_out_of_a_row_that_has_an_uncontended_one(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir alone = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        ProjectBuilds.RunDir first = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        ProjectBuilds.RunDir second = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        ProjectBuilds.RunDir other = ProjectBuilds.openRun(root, "g:other", root.resolve("other"));
        window(first, 0, 100);
        window(second, 50, 150);
        window(other, 90, 120);
        window(alone, 200, 300);
        Files.writeString(first.metricsFile(), """
                module.a.task.compile-java.wall-ms = 40
                module.b.task.run-tests.wall-ms = 40
                """);
        Files.writeString(second.metricsFile(), """
                module.a.task.compile-java.wall-ms = 45
                module.b.task.run-tests.wall-ms = 45
                """);
        Files.writeString(alone.metricsFile(), """
                module.a.task.compile-java.wall-ms = 10
                """);
        MetricsHarvest.get().configure(50, 90);
        MetricsHarvest.get().runOnce(root);

        String pm = Files.readString(first.projectHome().resolve(ProjectBuilds.PROJECT_METRICS));
        assertThat(pm)
                .contains("[mean]\nmodule.a.task.compile-java.wall-ms = 10\n")
                .contains("module.b.task.run-tests.wall-ms = 42.5")
                .contains("[count]\nmodule.a.task.compile-java.wall-ms = 1\nmodule.b.task.run-tests.wall-ms = 2\n");
        assertThat(Files.readString(second.runDir().resolve(RunContention.SIDECAR)))
                .as("the second run overlapped the first and the other project's run")
                .contains("overlapping-runs = 2");
        assertThat(Files.readString(first.runDir().resolve(RunContention.SIDECAR)))
                .as("the first run overlapped the second and the other project's run")
                .contains("overlapping-runs = 2");
        assertThat(alone.runDir().resolve(RunContention.SIDECAR)).doesNotExist();
    }

    private static void window(ProjectBuilds.RunDir run, long start, long end) throws Exception {
        Files.writeString(
                run.runDir().resolve(ProjectBuilds.RECORD),
                "{\n  \"schema\": 1,\n  \"startedAt\": " + start + ",\n  \"finishedAt\": " + end + "\n}\n");
    }

    /**
     * Class walls are one table per package of a module in the run file and in the ledger alike,
     * the classes by their simple names; a default-package class sits under the module's own table.
     */
    @Test
    void class_walls_are_one_table_per_package_with_simple_names(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        Files.writeString(run.metricsFile(), """
                workspace.wall-ms = 1000
                module.server/io.task.run-tests.wall-ms = 400

                [test-class."server/io"."com.example"]
                IoTest = 300
                SlowTest = 5000

                [test-class."server/io"."com.example.db"]
                DbTest = 70

                [test-class."_"]
                RootTest = 20
                """);
        MetricsHarvest.get().configure(50, 90);
        MetricsHarvest.get().runOnce(root);
        String pm = Files.readString(run.projectHome().resolve(ProjectBuilds.PROJECT_METRICS));
        assertThat(pm)
                .contains("module.server/io.task.run-tests.wall-ms = 400")
                .contains("[test-class.\"server/io\".\"com.example\"]\nIoTest = 300\nSlowTest = 5000\n")
                .contains("[test-class.\"server/io\".\"com.example.db\"]\nDbTest = 70\n")
                .contains("[test-class.\"_\"]\nRootTest = 20\n")
                .doesNotContain("com.example.IoTest")
                .doesNotContain("com.example.IoTest.wall-ms");
        assertThat(pm.indexOf("[count]")).as("the class tables close the file").isLessThan(pm.indexOf("[test-class."));
        AggregatedMetrics agg = AggregatedMetrics.load(root, "g:demo", root.resolve("proj"));
        assertThat(agg.testClassWallMs(root.resolve("proj/server/io").toString(), "com.example.SlowTest"))
                .hasValue(5000);
        assertThat(agg.testClassWallMs(root.resolve("proj/server/io").toString(), "com.example.db.DbTest"))
                .hasValue(70);
        assertThat(agg.testClassWallMs(root.resolve("proj").toString(), "RootTest"))
                .hasValue(20);
        assertThat(agg.classWalls())
                .containsOnlyKeys(
                        AggregatedMetrics.sanitize(
                                root.resolve("proj/server/io").toString()),
                        AggregatedMetrics.sanitize(root.resolve("proj").toString()));
    }

    @Test
    void retention_caps_run_count(@TempDir Path root) throws Exception {
        Path home = ProjectBuilds.projectHome(root, "g:cap", root.resolve("p"));
        Files.createDirectories(home.resolve(ProjectBuilds.RUNS));
        for (int i = 1; i <= 5; i++) {
            Path run = home.resolve(ProjectBuilds.RUNS).resolve(Integer.toString(i));
            Files.createDirectories(run);
            Files.writeString(run.resolve(ProjectBuilds.METRICS), "workspace.wall-ms = " + i + "00\n");
        }
        MetricsHarvest h = MetricsHarvest.get();
        h.configure(2, 90);
        h.runOnce(root);
        assertThat(ProjectBuilds.listRuns(home)).hasSize(2);
        // Newest (highest number) kept
        assertThat(ProjectBuilds.listRuns(home).get(0).getFileName().toString()).isEqualTo("5");
    }

    @Test
    void isHostKey_classifies() {
        // Nothing writes a `host.` prefix: a bare prefixed key has no producer and is not a host key,
        // while a prefixed *rate* row from an older store is still one by its suffix.
        assertThat(MetricsHarvest.isHostKey("host.x")).isFalse();
        assertThat(MetricsHarvest.isHostKey("host.run-tests-per-method-ms")).isTrue();
        assertThat(MetricsHarvest.isHostKey("task.compile-java.wall-ms")).isTrue();
        assertThat(MetricsHarvest.isHostKey("run-tests-per-method-ms")).isTrue();
        assertThat(MetricsHarvest.isHostKey("native-image-ms-per-mib")).isTrue();
        assertThat(MetricsHarvest.isHostKey("native-image-floor-ms")).isTrue();
        assertThat(MetricsHarvest.isHostKey("workspace.wall-ms")).isFalse();
        assertThat(MetricsHarvest.isHostKey("module./p.task.x.wall-ms")).isFalse();
        // The step.* key family was renamed to task.*; a row in the retired shape is not read.
        assertThat(MetricsHarvest.isHostKey("step.compile-java.wall-ms")).isFalse();
    }

    @Test
    void isContinuousMeanKey_excludes_run_harvest() {
        assertThat(MetricsHarvest.isContinuousMeanKey("native-image-ms-per-mib"))
                .isTrue();
        assertThat(MetricsHarvest.isContinuousMeanKey("native-image-floor-ms")).isTrue();
        assertThat(MetricsHarvest.isContinuousMeanKey("compile-java-per-source-ms"))
                .isTrue();
        assertThat(MetricsHarvest.isContinuousMeanKey("task.native-image.wall-ms"))
                .isFalse();
        assertThat(MetricsHarvest.isContinuousMeanKey("phase.native.wall-ms")).isFalse();
        assertThat(MetricsHarvest.isContinuousMeanKey("module./p.task.native-image.wall-ms"))
                .isFalse();
    }

    @Test
    void harvest_preserves_continuous_native_rates(@TempDir Path root) throws Exception {
        Path host = ProjectBuilds.hostMetricsFile(root);
        Files.createDirectories(host.getParent());
        Files.writeString(host, """
                # host-metrics
                [mean]
                task.compile-java.wall-ms = 100
                native-image-ms-per-mib = 14500.5
                native-image-floor-ms = 11200

                [calibration]
                schema = 1
                ms-per-weight = 150
                """);
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        Files.writeString(run.metricsFile(), "task.compile-java.wall-ms = 220\n");
        MetricsHarvest.get().configure(50, 90);
        MetricsHarvest.get().runOnce(root);
        String hm = Files.readString(host);
        assertThat(hm)
                .contains("native-image-ms-per-mib = 14500.5")
                .contains("native-image-floor-ms = 11200")
                .contains("task.compile-java.wall-ms");
    }

    @Test
    void isImplausibleHeavyWall_drops_native_restore_blips() {
        assertThat(MetricsHarvest.isImplausibleHeavyWall("module./p.task.native-image.wall-ms", 32.0))
                .isTrue();
        assertThat(MetricsHarvest.isImplausibleHeavyWall("module./p.task.native-image.wall-ms", 32_000.0))
                .isFalse();
        assertThat(MetricsHarvest.isImplausibleHeavyWall("task.write-image.wall-ms", 100.0))
                .isTrue();
        assertThat(MetricsHarvest.isImplausibleHeavyWall("task.run-tests.wall-ms", 50.0))
                .isFalse();
    }

    @Test
    void harvest_skips_implausible_native_walls(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        Files.writeString(run.metricsFile(), """
                module./p.task.native-image.wall-ms = 32
                module./p.task.run-tests.wall-ms = 28000
                """);
        MetricsHarvest.get().configure(50, 90);
        MetricsHarvest.get().runOnce(root);
        String pm = Files.readString(run.projectHome().resolve(ProjectBuilds.PROJECT_METRICS));
        assertThat(pm).contains("run-tests").doesNotContain("native-image");
    }
}
