// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEffortTest {

    @TempDir
    Path stateDir;

    private String prevStateDir;
    private String prevBuildsDir;

    @BeforeEach
    void isolateHostState() {
        // Pin state + builds so host-metrics.toml (cpuScale, learned native rates) cannot leak
        // into assertions (JK-1818). JK_BUILDS_DIR is required too: a set JK_HOME would otherwise
        // ignore JK_STATE_DIR for builds/. The dogfood-conditional test opts back in explicitly.
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        prevBuildsDir = System.getProperty("jk.env.JK_BUILDS_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        System.setProperty("jk.env.JK_BUILDS_DIR", stateDir.resolve("builds").toString());
        Calibration.invalidateMemo();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @AfterEach
    void restoreHostState() {
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        if (prevBuildsDir == null) System.clearProperty("jk.env.JK_BUILDS_DIR");
        else System.setProperty("jk.env.JK_BUILDS_DIR", prevBuildsDir);
        Calibration.invalidateMemo();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @Test
    void effective_bytes_discount_deps() {
        long eff = NativeEffort.effectiveInputBytes(1_000_000, 10_000_000);
        assertThat(eff).isEqualTo(1_000_000 + Math.round(10_000_000 * 0.12));
        assertThat(eff).isLessThan(3_000_000);
    }

    @Test
    void size_model_for_cli_like_app_tracks_reference_with_host_scale() {
        // Dogfood-shaped app: ~1.15 MiB jar + ~2.7 MiB deps → ~1.4 MiB effective.
        // Cold path is reference anchors × calibration cpuScale × MODEL_PAD (no install Graal
        // probe). Isolated builds dir → no learned rates; scale is 1.0 unless cold priors leak.
        long app = 1_150_000;
        long deps = 2_700_000;
        long eff = NativeEffort.effectiveInputBytes(app, deps);
        double mib = eff / (1024.0 * 1024.0);
        Calibration cal = Calibration.load();
        double scale = cal.hasColdPriors() ? NativeEffort.nativeColdScale(cal.cpuScale()) : 1.0;
        long expected = Math.round((NativeEffort.REF_FLOOR_MS * scale + NativeEffort.REF_MS_PER_MIB * scale * mib)
                * NativeEffort.MODEL_PAD);
        expected = Math.max(NativeEffort.WALL_FLOOR_MS, Math.min(NativeEffort.MAX_NATIVE_MS, expected));

        long ms = NativeEffort.sizeModelWallMs(eff);
        // ±1 ms: product rounds floor+slope*mib then pad; we recompute the same formula.
        assertThat(ms).isBetween(expected - 1, expected + 1);
        // Still a thin-CLI ballpark — not multi-minute, not a token blip.
        assertThat(ms).isBetween(10_000L, 90_000L);
    }

    @Test
    void size_model_grows_with_effective_bytes() {
        long small = NativeEffort.sizeModelWallMs(500_000);
        long large = NativeEffort.sizeModelWallMs(5_000_000);
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void native_cold_scale_uses_full_calibration_clamp() {
        // Full clamp: faster host deflates cold native, slower inflates (no install Graal probe).
        assertThat(NativeEffort.nativeColdScale(0.65)).isEqualTo(Calibration.HOST_SCALE_MIN);
        assertThat(NativeEffort.nativeColdScale(1.0)).isEqualTo(1.0);
        assertThat(NativeEffort.nativeColdScale(1.4)).isEqualTo(1.4);
        assertThat(NativeEffort.nativeColdScale(0.1)).isEqualTo(Calibration.HOST_SCALE_MIN);
        assertThat(NativeEffort.nativeColdScale(5.0)).isEqualTo(Calibration.HOST_SCALE_MAX);
    }

    @Test
    void host_samples_are_size_normalized() {
        // 32s wall on 1.43 MiB effective → floor ~35% and positive slope (alien reuse units).
        var samples = NativeEffort.hostSamples(32_000, 1_500_000);
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).key()).isEqualTo(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB);
        assertThat(samples.get(1).key()).isEqualTo(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS);
        assertThat(samples.get(0).ms()).isBetween(5_000.0, 40_000.0);
        assertThat(samples.get(1).ms()).isBetween(5_000.0, 20_000.0);
    }

    @Test
    void host_samples_from_real_wall() {
        var samples = NativeEffort.hostSamples(33_000, 1_200_000);
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).ms()).isPositive();
    }

    @Test
    void host_samples_reject_restore_noise() {
        assertThat(NativeEffort.hostSamples(32, 1_000_000)).isEmpty();
    }

    @Test
    void sum_existing_bytes_files(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.jar");
        Path b = dir.resolve("b.jar");
        Files.writeString(a, "x".repeat(1000));
        Files.writeString(b, "y".repeat(2000));
        assertThat(NativeEffort.sumExistingBytes(List.of(a, b))).isEqualTo(3000);
    }

    @Test
    void own_wall_wins_without_pad_when_metrics_present() {
        // Dogfood-conditional: opts back into the real host state the class-level isolation
        // hides (every assertion below is guarded by early returns / relative bounds).
        restoreHostState();
        Path cli = Path.of("clients/cli").toAbsolutePath().normalize();
        if (!Files.isRegularFile(cli.resolve("jk.toml"))) return;
        long own = EffortWeights.stepOkAvgMillisOwn(
                BuildMetrics.load(BuildMetrics.defaultFile()), cli.toString(), "native-image");
        if (own < NativeEffort.WALL_FLOOR_MS) return;
        long est = NativeEffort.wallMillis(cli);
        assertThat(est).isEqualTo(own);
        long model = NativeEffort.sizeModelWallMs(NativeEffort.estimateInputBytes(cli));
        // Size model must not be ~2× a known real wall
        assertThat(model).isLessThan(own * 2);
        // And must not massively under-shoot a known real wall (the post-discount regression)
        assertThat(model).isGreaterThan(Math.round(own * 0.7));
    }
}
