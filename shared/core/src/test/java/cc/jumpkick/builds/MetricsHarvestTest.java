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
                schema = 4
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
