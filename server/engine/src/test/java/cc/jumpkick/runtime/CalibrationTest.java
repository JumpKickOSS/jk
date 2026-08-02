// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.model.JkVersion;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Host calibration: the probe-derived anchor, TOML round-trip, staleness, and refine fold. */
class CalibrationTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void deriveMsPerWeight_anchors_measured_time_to_the_synthetic_weight() {
        // Full model weight from HardwareProbe; wall = fork+javac+fork (test-worker proxy).
        double mpw = Calibration.deriveMsPerWeight(180, 180);
        assertThat(mpw).isGreaterThan(0);
        assertThat(mpw).isCloseTo(HardwareProbe.deriveMsPerWeight(180, 180, 0, 0, 0, 0), within(1e-6));
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
    void round_trips_through_toml(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("calibration.toml");
        Calibration written = Calibration.testInstance(42.5, true, JkVersion.VERSION, NOW);
        Calibration.writeTo(f, written);
        Calibration read = Calibration.readFrom(f, NOW);
        assertThat(read.present()).isTrue();
        assertThat(read.measured()).isTrue();
        assertThat(read.msPerWeight()).isCloseTo(42.5, within(1e-3));
    }

    @Test
    void missing_file_is_absent_and_falls_back_to_the_constant() {
        Calibration absent = Calibration.readFrom(Path.of("/no/such/calibration.toml"), NOW);
        assertThat(absent.present()).isFalse();
        assertThat(absent.msPerWeight()).isEqualTo((double) EffortWeights.MS_PER_WEIGHT);
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
    void stale_file_reads_as_absent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("calibration.toml");
        Calibration.writeTo(f, Calibration.testInstance(42.5, true, "0.0.0-OLD", NOW));
        assertThat(Calibration.readFrom(f, NOW).present()).isFalse();
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
    void learned_rates_round_trip_toml(@TempDir Path dir) throws Exception {
        HostLearnedRates learned = new HostLearnedRates()
                .withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 42, 0)
                .withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 48, 0);
        Calibration written = Calibration.testInstance(100.0, true, JkVersion.VERSION, NOW, learned, 200, 15, 20);
        Path f = dir.resolve("calibration.toml");
        Calibration.writeTo(f, written);
        Calibration read = Calibration.readFrom(f, NOW);
        assertThat(read.present()).isTrue();
        // Scalars only on disk (JK-1377) — trimmed mean persists as a single prior sample.
        assertThat(read.learned().sampleCount(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))
                .isEqualTo(1);
        assertThat(read.learned().meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))
                .hasValueCloseTo(45.0, within(1e-6));
        assertThat(read.probeTestMethodMs()).isEqualTo(15);
        assertThat(read.probeTestSuiteStartupMs()).isEqualTo(200);
        assertThat(read.testMethodMs()).isEqualTo(45); // learned wins over probe
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
}
