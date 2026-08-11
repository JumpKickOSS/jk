// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeEffortTest {

    @Test
    void effective_bytes_discount_deps() {
        long eff = NativeEffort.effectiveInputBytes(1_000_000, 10_000_000);
        assertThat(eff).isEqualTo(1_000_000 + Math.round(10_000_000 * 0.12));
        assertThat(eff).isLessThan(3_000_000);
    }

    @Test
    void size_model_for_cli_like_app_is_near_half_minute_not_two() {
        long app = 1_150_000;
        long deps = 2_700_000;
        long eff = NativeEffort.effectiveInputBytes(app, deps);
        long ms = NativeEffort.sizeModelWallMs(eff);
        assertThat(ms).isBetween(15_000L, 55_000L);
    }

    @Test
    void size_model_grows_with_effective_bytes() {
        long small = NativeEffort.sizeModelWallMs(500_000);
        long large = NativeEffort.sizeModelWallMs(5_000_000);
        assertThat(large).isGreaterThan(small);
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
    }
}
