// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The materialize bound follows cores and heap, inside its floor and ceiling. */
class DownloadSlotsTest {

    private static final long MIB = 1L << 20;

    @Test
    void four_slots_per_core_when_the_heap_allows_it() {
        assertThat(DownloadSlots.width(4, 1024 * MIB)).isEqualTo(16);
    }

    @Test
    void one_slot_per_four_mebibytes_of_heap_when_that_is_tighter() {
        assertThat(DownloadSlots.width(24, 128 * MIB)).isEqualTo(32);
    }

    @Test
    void the_default_engine_heap_on_a_wide_host_lands_near_the_ceiling() {
        assertThat(DownloadSlots.width(24, 256 * MIB)).isEqualTo(64);
        assertThat(DownloadSlots.width(64, 4096 * MIB)).isEqualTo(DownloadSlots.MAX_WIDTH);
    }

    @Test
    void a_small_host_keeps_the_floor() {
        assertThat(DownloadSlots.width(1, 64 * MIB)).isEqualTo(DownloadSlots.MIN_WIDTH);
        assertThat(DownloadSlots.width(2, 16 * MIB)).isEqualTo(DownloadSlots.MIN_WIDTH);
    }

    @Test
    void an_uncapped_heap_is_bounded_by_cores_alone() {
        assertThat(DownloadSlots.width(4, Long.MAX_VALUE)).isEqualTo(16);
    }

    @Test
    void a_slot_taken_is_a_slot_given_back() throws Exception {
        int before = DownloadSlots.available();
        DownloadSlots.acquire();
        assertThat(DownloadSlots.available()).isEqualTo(before - 1);
        DownloadSlots.release();
        assertThat(DownloadSlots.available()).isEqualTo(before);
    }
}
