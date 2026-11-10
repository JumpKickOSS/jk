// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** jk never fills a disk: under 10% or 2 GiB free, no heavy blob goes into a cache. */
class DiskGuardTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Path volume = Path.of("/vol");

    @BeforeEach
    void setUp() {
        DiskGuard.resetForTest(now::get);
    }

    @AfterEach
    void tearDown() {
        DiskGuard.resetForTest(Clock.SYSTEM::millis);
    }

    @Test
    void a_volume_is_critical_under_ten_percent_or_two_gib_free() {
        assertThat(DiskGuard.critical(new JkCacheConfig.DiskSpace(1000 * GIB, 99 * GIB)))
                .as("9.9% of a terabyte")
                .isTrue();
        assertThat(DiskGuard.critical(new JkCacheConfig.DiskSpace(1000 * GIB, 101 * GIB)))
                .isFalse();
        assertThat(DiskGuard.critical(new JkCacheConfig.DiskSpace(16 * GIB, (long) (1.9 * GIB))))
                .as("12% of a small disk, but under the 2 GiB floor")
                .isTrue();
        assertThat(DiskGuard.critical((JkCacheConfig.DiskSpace) null))
                .as("no probe")
                .isFalse();
    }

    @Test
    void a_heavy_blob_is_refused_on_a_critical_volume_and_the_refusal_wakes_the_listeners() {
        AtomicInteger woken = new AtomicInteger();
        DiskGuard.onRefused(woken::incrementAndGet);
        DiskGuard.putProbeForTest(volume, new JkCacheConfig.DiskSpace(100 * GIB, GIB));

        assertThat(DiskGuard.admits(volume, DiskGuard.HEAVY_BYTES - 1))
                .as("a small blob is never refused")
                .isTrue();
        assertThat(woken).hasValue(0);
        assertThat(DiskGuard.admits(volume, DiskGuard.HEAVY_BYTES)).isFalse();
        assertThat(woken).hasValue(1);

        DiskGuard.putProbeForTest(volume, new JkCacheConfig.DiskSpace(100 * GIB, 50 * GIB));
        assertThat(DiskGuard.admits(volume, 10 * DiskGuard.HEAVY_BYTES))
                .as("room again")
                .isTrue();
    }

    @Test
    void a_probe_is_trusted_for_five_seconds_then_taken_again() {
        DiskGuard.putProbeForTest(volume, new JkCacheConfig.DiskSpace(100 * GIB, GIB));
        assertThat(DiskGuard.critical(volume)).isTrue();
        now.addAndGet(DiskGuard.PROBE_MILLIS);
        // /vol does not exist: the fresh probe answers nothing, which is never critical.
        assertThat(DiskGuard.critical(volume)).isFalse();
    }

    @Test
    void the_reason_line_names_free_and_total_space() {
        assertThat(DiskGuard.describe(new JkCacheConfig.DiskSpace(100 * GIB, (long) (1.5 * GIB))))
                .isEqualTo("1.5 GiB free of 100.0 GiB (2%)");
    }
}
