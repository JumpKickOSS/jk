// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationTextTest {

    @Test
    void clock_is_zero_padded_below_the_leading_unit() {
        assertThat(DurationText.clock(0)).isEqualTo("0s");
        assertThat(DurationText.clock(14)).isEqualTo("14s");
        assertThat(DurationText.clock(59)).isEqualTo("59s");
        assertThat(DurationText.clock(60)).isEqualTo("1m 00s");
        assertThat(DurationText.clock(62)).isEqualTo("1m 02s");
        assertThat(DurationText.clock(3600)).isEqualTo("1h 00m 00s");
        assertThat(DurationText.clock(3909)).isEqualTo("1h 05m 09s");
        assertThat(DurationText.clock(-5)).isEqualTo("0s");
    }

    @Test
    void clock_millis_floors_and_clamps() {
        assertThat(DurationText.clockMillis(999)).isEqualTo("0s");
        assertThat(DurationText.clockMillis(1000)).isEqualTo("1s");
        assertThat(DurationText.clockMillis(-1)).isEqualTo("0s");
    }

    @Test
    void human_switches_units_at_each_boundary() {
        assertThat(DurationText.human(Duration.ofMillis(712))).isEqualTo("712ms");
        assertThat(DurationText.human(Duration.ofMillis(999))).isEqualTo("999ms");
        assertThat(DurationText.human(Duration.ofMillis(1000))).isEqualTo("1.0s");
        assertThat(DurationText.human(Duration.ofMillis(3100))).isEqualTo("3.1s");
        assertThat(DurationText.human(Duration.ofSeconds(59))).isEqualTo("59.0s");
        assertThat(DurationText.human(Duration.ofSeconds(60))).isEqualTo("1m 0s");
        assertThat(DurationText.human(Duration.ofSeconds(124))).isEqualTo("2m 4s");
        assertThat(DurationText.human(Duration.ofSeconds(3600))).isEqualTo("1h 0m 0s");
        assertThat(DurationText.human(
                        Duration.ofDays(1).plusHours(12).plusMinutes(13).plusSeconds(5)))
                .isEqualTo("1d 12h 13m 5s");
    }

    @Test
    void coarse_floor_never_over_states() {
        assertThat(DurationText.coarseFloor(0)).isEqualTo("<1s");
        assertThat(DurationText.coarseFloor(999)).isEqualTo("<1s");
        assertThat(DurationText.coarseFloor(1000)).isEqualTo("1s");
        assertThat(DurationText.coarseFloor(8_400)).isEqualTo("8s");
        assertThat(DurationText.coarseFloor(59_999)).isEqualTo("59s");
        assertThat(DurationText.coarseFloor(60_000)).isEqualTo("1m 0s");
        assertThat(DurationText.coarseFloor(80_000)).isEqualTo("1m 20s");
        assertThat(DurationText.coarseFloor(3_600_000)).isEqualTo("60m 0s");
    }

    @Test
    void omit_zero_drops_empty_components_and_dashes_negatives() {
        assertThat(DurationText.omitZero(-1)).isEqualTo("—");
        assertThat(DurationText.omitZero(0)).isEqualTo("0ms");
        assertThat(DurationText.omitZero(999)).isEqualTo("999ms");
        assertThat(DurationText.omitZero(1000)).isEqualTo("1s");
        assertThat(DurationText.omitZero(22_000)).isEqualTo("22s");
        assertThat(DurationText.omitZero(59_000)).isEqualTo("59s");
        assertThat(DurationText.omitZero(60_000)).isEqualTo("1m");
        assertThat(DurationText.omitZero(62_000)).isEqualTo("1m 2s");
        assertThat(DurationText.omitZero(3_600_000)).isEqualTo("1h");
        assertThat(DurationText.omitZero(86_400_000L + 4L * 3_600_000L + 12_000L))
                .isEqualTo("1d 4h 12s");
    }
}
