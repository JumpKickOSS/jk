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
    void size_model_grows_with_input_bytes() {
        long small = NativeEffort.sizeModelWallMs(500_000); // ~0.5 MiB
        long large = NativeEffort.sizeModelWallMs(5_000_000); // ~5 MiB
        assertThat(large).isGreaterThan(small);
        assertThat(small).isGreaterThanOrEqualTo(NativeEffort.WALL_FLOOR_MS);
    }

    @Test
    void host_samples_from_real_wall() {
        var samples = NativeEffort.hostSamples(33_000, 1_200_000);
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).key()).isEqualTo(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB);
        assertThat(samples.get(0).ms()).isPositive();
        assertThat(samples.get(1).key()).isEqualTo(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS);
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
    void pad_over_reserves_mildly() {
        long raw = 30_000;
        assertThat(NativeEffort.pad(raw)).isGreaterThan(raw);
        assertThat(NativeEffort.pad(raw)).isLessThan(raw * 2);
    }
}
