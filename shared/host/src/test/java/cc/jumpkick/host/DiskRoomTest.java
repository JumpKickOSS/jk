// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskRoomTest {

    private static final long GIB = 1L << 30;

    @Test
    void the_floor_is_one_gib_or_two_percent_and_at_most_two_gib() {
        assertThat(DiskRoom.floorBytes(15L << 30)).isEqualTo(GIB);
        assertThat(DiskRoom.floorBytes(80L << 30)).isEqualTo((80L << 30) / 50);
        assertThat(DiskRoom.floorBytes(200L << 30)).isEqualTo(2 * GIB);
        assertThat(DiskRoom.floorBytes(0)).isEqualTo(GIB);
    }

    @Test
    void a_volume_fits_nothing_past_everything_it_has(@TempDir Path dir) {
        assertThat(DiskRoom.fits(dir.resolve("not/yet"), Long.MAX_VALUE / 2))
                .as("judged by the nearest existing ancestor's volume")
                .isFalse();
    }
}
