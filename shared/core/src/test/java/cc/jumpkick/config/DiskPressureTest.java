// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiskPressureTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static JkCacheConfig.DiskSpace disk(long totalGib, long freeGib) {
        return new JkCacheConfig.DiskSpace(totalGib * GIB, freeGib * GIB);
    }

    @Test
    void plenty_of_room_is_no_pressure() {
        assertThat(DiskPressure.factor(disk(1000, 700))).isEqualTo(DiskPressure.NONE);
        assertThat(DiskPressure.factor(null)).isEqualTo(DiskPressure.NONE);
    }

    @Test
    void the_windows_shrink_in_steps_as_the_volume_fills() {
        assertThat(DiskPressure.factor(disk(1000, 150))).isEqualTo(0.5);
        assertThat(DiskPressure.factor(disk(1000, 90))).isEqualTo(0.25);
        assertThat(DiskPressure.factor(disk(1000, 40))).isEqualTo(DiskPressure.EMERGENCY);
    }

    @Test
    void the_worse_of_the_share_and_the_bytes_decides() {
        assertThat(DiskPressure.factor(disk(100, 25)))
                .as("25% free is comfortable as a share, thin in bytes")
                .isEqualTo(0.5);
        assertThat(DiskPressure.factor(disk(8000, 300)))
                .as("300 GiB is a lot of bytes, under 4% of the volume")
                .isEqualTo(DiskPressure.EMERGENCY);
    }

    @Test
    void emergency_is_the_bottom_step() {
        assertThat(DiskPressure.emergency(DiskPressure.factor(disk(500, 5)))).isTrue();
        assertThat(DiskPressure.emergency(DiskPressure.factor(disk(1000, 700)))).isFalse();
    }
}
