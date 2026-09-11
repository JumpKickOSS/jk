// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Host calibration model: the probe-derived anchor, staleness policy, and refine fold. */
class CalibrationTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void deriveMsPerWeight_anchors_measured_time_to_the_synthetic_weight() {
        // Full model weight from HardwareProbe; wall = fork+javac+fork (test-worker proxy).
        double mpw = Calibration.deriveMsPerWeight(180, 180);
        assertThat(mpw).isGreaterThan(0);
        assertThat(mpw).isCloseTo(HardwareProbe.deriveMsPerWeight(180, 180, 0, 0, 0, 0, 0, 0, false), within(1e-6));
    }

    @Test
    void hardware_probe_model_weight_is_positive() {
        assertThat(HardwareProbe.modelWeight()).isGreaterThan(EffortWeights.TEST_STARTUP);
    }

    @Test
    void max_warm_is_pessimistic() {
        assertThat(HardwareProbe.maxWarm(List.of(10L, 20L, 15L, 40L))).isEqualTo(40L);
        // first sample dropped as cold-cache warmup
        assertThat(HardwareProbe.maxWarm(List.of(100L, 12L, 11L))).isEqualTo(12L);
    }

    @Test
    void a_different_jk_version_is_stale() {
        assertThat(Calibration.stale("0.0.0-OLD", NOW, NOW)).isTrue();
        assertThat(Calibration.stale(JkVersion.VERSION, NOW, NOW)).isFalse();
    }

    @Test
    void an_aged_file_is_stale() {
        long updated = NOW - 61 * DAY; // TTL is ~60 days
        assertThat(Calibration.stale(JkVersion.VERSION, updated, NOW)).isTrue();
        assertThat(Calibration.stale(JkVersion.VERSION, NOW - 30 * DAY, NOW)).isFalse();
    }

    @Test
    void refine_replaces_a_probe_bootstrap_on_the_first_real_measurement() {
        Calibration probe = Calibration.testInstance(19.0, false, JkVersion.VERSION, NOW); // measured=false
        Calibration refined = Calibration.foldRefine(probe, 158.0, NOW + 1);
        assertThat(refined.measured()).isTrue();
        assertThat(refined.msPerWeight()).isCloseTo(158.0, within(1e-6)); // replaced, not EWMA
    }

    @Test
    void refine_ewma_smooths_once_measured() {
        Calibration measured = Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW);
        Calibration refined = Calibration.foldRefine(measured, 200.0, NOW + 1);
        assertThat(refined.msPerWeight()).isCloseTo(140.0, within(1e-6)); // 0.4*200 + 0.6*100
    }

    @Test
    void cold_run_tests_wall_uses_baseline_times_host_scale_not_empty_probe() {
        // Probe residual method=20 must NOT become absolute cold cost (that under-shoots real suites).
        Calibration cal =
                Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW, new HostLearnedRates(), 100, 20, 10);
        long method = cal.testMethodMs();
        long startup = cal.testSuiteStartupMs();
        // Product baseline × scale × cold bias — well above empty-probe residual.
        assertThat(method).isGreaterThanOrEqualTo(20);
        assertThat(method).isLessThanOrEqualTo(Calibration.BASELINE_METHOD_MS * 2);
        assertThat(startup).isGreaterThanOrEqualTo(100);
        // Default coldStepWallMs uses 1 worker → serial body.
        long expectedSerial = startup + 10 * method;
        assertThat(cal.coldStepWallMs("run-tests", 10)).isEqualTo(expectedSerial);
        // Within-module -w is capped by COLD_MAX_TEST_PARALLEL (not fully linear).
        int w = Calibration.coldTestParallel(8);
        long expectedCapped = startup + (10 * method + w - 1) / w;
        assertThat(cal.coldStepWallMs("run-tests", 10, 8)).isEqualTo(expectedCapped);
        assertThat(w).isEqualTo(Calibration.COLD_MAX_TEST_PARALLEL);
    }

    @Test
    void host_scale_clamps_and_prefers_identity_when_uncalibrated() {
        assertThat(Calibration.hostScale()).isEqualTo(1.0);
        assertThat(Calibration.clampScale(0.1)).isEqualTo(Calibration.HOST_SCALE_MIN);
        assertThat(Calibration.clampScale(5.0)).isEqualTo(Calibration.HOST_SCALE_MAX);
        // Slower host → higher scale → longer baseline.
        long slow = Calibration.scaleBaseline(100, 2.0);
        long fast = Calibration.scaleBaseline(100, 0.5);
        assertThat(slow).isGreaterThan(fast);
        assertThat(fast).isGreaterThanOrEqualTo(Math.round(100 * Calibration.HOST_SCALE_MIN * Calibration.COLD_BIAS));
    }

    @Test
    void faster_host_probe_lowers_cold_method_cost_but_not_below_scale_floor() {
        // testInstance uses small component ms (fast host relative to REF_*).
        Calibration cal = Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW);
        assertThat(cal.cpuScale()).isEqualTo(Calibration.HOST_SCALE_MIN);
        long method = cal.testMethodMs();
        // At least baseline × min scale × bias, not empty-probe 5ms.
        long floor = Math.round(Calibration.BASELINE_METHOD_MS * Calibration.HOST_SCALE_MIN * Calibration.COLD_BIAS);
        assertThat(method).isEqualTo(floor);
    }

    @Test
    void derive_method_ms_clamps_synth_body() {
        // 8 worker methods, 40 ms body → 5 ms/method (floor) — diagnostic residual only.
        assertThat(Calibration.deriveMethodMs(40, false, 0)).isEqualTo(Calibration.METHOD_MS_FLOOR);
    }

    /**
     * A measured calibration on disk must end the bootstrap probe for good. {@link
     * HostMetricsFile#readFrom} already rejects any schema but {@link Calibration#SCHEMA}, so the
     * skip is pinned against the constant, never against a literal a rolled constant could strand.
     */
    @Test
    void a_measured_calibration_on_disk_ends_the_bootstrap_probe(@TempDir Path home) throws Exception {
        withJkHome(home, () -> {
            Calibration.invalidateMemo();
            Path file = JkDirs.builds().resolve("host-metrics.toml");
            Files.createDirectories(file.getParent());
            // 12345.0 is a value no real probe produces: if ensure() probes, it is overwritten.
            HostMetricsFile.writeTo(
                    file, Calibration.testInstance(12345.0, true, JkVersion.VERSION, System.currentTimeMillis()));
            String before = Files.readString(file);
            Calibration.invalidateMemo();

            assertThat(Calibration.needsProbe()).as("nothing left to bootstrap").isFalse();

            // ensureAnnounced is the one place that decides whether any client says
            // "Calibrating host…" — build and explain both go through it, and nothing outside
            // the engine gets to work the answer out for itself.
            List<String> announced = new ArrayList<>();
            Calibration kept = Calibration.ensureAnnounced(
                    null, (stage, done, total, label) -> announced.add(stage + " " + done + "/" + total));
            assertThat(announced).as("a calibrated host announces nothing").isEmpty();
            assertThat(kept.msPerWeight()).isCloseTo(12345.0, within(1e-6));
            assertThat(Files.readString(file))
                    .as("ensure did not rewrite the file")
                    .isEqualTo(before);
            assertThat(Files.exists(Calibration.failureMarker()))
                    .as("no probe ran, so no probe failed")
                    .isFalse();
        });
        Calibration.invalidateMemo();
    }

    /**
     * Relocate the whole jk layout for the body. {@code jk.env.<NAME>} is {@link JkDirs}'s
     * documented in-process seam — env vars are fixed at JVM start, system properties are not.
     */
    private static void withJkHome(Path home, ThrowingRunnable body) throws Exception {
        String key = "jk.env.JK_HOME";
        String previous = System.getProperty(key);
        System.setProperty(key, home.toAbsolutePath().toString());
        try {
            body.run();
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
