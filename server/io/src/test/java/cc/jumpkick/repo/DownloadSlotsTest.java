// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.http.HostRateLimiter;
import java.util.List;
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

    @Test
    void leg_slots_are_a_pool_per_host_sized_from_its_request_permits() throws Exception {
        assertThat(DownloadSlots.legWidth(6)).isEqualTo(6 * DownloadSlots.LEGS_PER_PERMIT);
        for (String host :
                List.of("repo.example.org", "repo1.maven.org", "maven-central.storage-download.googleapis.com")) {
            assertThat(DownloadSlots.legWidth(host))
                    .as("%s queues four legs per request permit", host)
                    .isEqualTo(HostRateLimiter.shared().permitsFor(host) * DownloadSlots.LEGS_PER_PERMIT);
        }
        int rows = DownloadSlots.available();
        int legs = DownloadSlots.legsAvailable("repo.example.org");
        assertThat(legs).isEqualTo(DownloadSlots.legWidth("repo.example.org"));
        DownloadSlots.acquireLeg("repo.example.org");
        assertThat(DownloadSlots.legsAvailable("repo.example.org")).isEqualTo(legs - 1);
        assertThat(DownloadSlots.legsAvailable("other.example.org"))
                .as("another host's queue is untouched")
                .isEqualTo(DownloadSlots.legWidth("other.example.org"));
        assertThat(DownloadSlots.available()).as("a leg takes no row slot").isEqualTo(rows);
        assertThat(DownloadSlots.tryAcquireLeg("repo.example.org")).isTrue();
        DownloadSlots.releaseLeg("repo.example.org");
        DownloadSlots.releaseLeg("repo.example.org");
        assertThat(DownloadSlots.legsAvailable("repo.example.org")).isEqualTo(legs);
    }
}
