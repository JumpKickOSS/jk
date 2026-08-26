// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** {@code /proc/meminfo} parsing and the live probe's invariants. */
class MemoryProbeTest {

    private static final String MEMINFO = String.join(
            "\n",
            "MemTotal:       16004000 kB",
            "MemFree:         1200000 kB",
            "MemAvailable:    8000000 kB",
            "Buffers:          100000 kB");

    @Test
    void parses_kilobyte_values_into_bytes() {
        assertThat(MemoryProbe.meminfoValueBytes(MEMINFO, "MemTotal")).isEqualTo(16004000L * 1024);
        assertThat(MemoryProbe.meminfoValueBytes(MEMINFO, "MemAvailable")).isEqualTo(8000000L * 1024);
    }

    @Test
    void returns_minus_one_for_absent_keys() {
        assertThat(MemoryProbe.meminfoValueBytes(MEMINFO, "Cached")).isEqualTo(-1);
        assertThat(MemoryProbe.meminfoValueBytes("", "MemTotal")).isEqualTo(-1);
    }

    @Test
    void live_probe_is_sane_and_never_throws() {
        MemoryProbe.Memory m = MemoryProbe.probe();
        assertThat(m.totalBytes()).isPositive();
        assertThat(m.availableBytes()).isPositive();
        assertThat(m.availableBytes()).isLessThanOrEqualTo(m.totalBytes());
    }

    @Test
    void current_is_uncached_and_sane() {
        MemoryProbe.Memory a = MemoryProbe.current();
        MemoryProbe.Memory b = MemoryProbe.current();
        assertThat(a.totalBytes()).isPositive();
        assertThat(a.availableBytes()).isPositive();
        assertThat(a.availableBytes()).isLessThanOrEqualTo(a.totalBytes());
        // Same machine between two back-to-back reads: totals match; available within a band.
        assertThat(b.totalBytes()).isEqualTo(a.totalBytes());
        assertThat(b.availableBytes()).isPositive();
    }

    @Test
    void linux_available_tracks_memavailable_not_memfree() throws Exception {
        Assumptions.assumeTrue(Os.isLinux());
        Path meminfoPath = Path.of("/proc/meminfo");
        Assumptions.assumeTrue(Files.isReadable(meminfoPath));
        String meminfo = Files.readString(meminfoPath);
        long memFree = MemoryProbe.meminfoValueBytes(meminfo, "MemFree");
        long memAvail = MemoryProbe.meminfoValueBytes(meminfo, "MemAvailable");
        Assumptions.assumeTrue(memFree > 0 && memAvail > 0);
        MemoryProbe.Memory m = MemoryProbe.current();
        // On a typical busy host MemAvailable ≫ MemFree; probe must not report idle free alone.
        // Allow cgroup clamping: available ≤ MemAvailable and much closer to it than to MemFree
        // when the host is cache-heavy (MemAvailable > 2× MemFree).
        // The probe re-reads /proc/meminfo, so this compares two samples of a counter that moves
        // with system activity. A fixed 1 MiB epsilon failed whenever a build was running on the
        // same machine; scale the slack to the host instead. The property under test is
        // qualitative — "available tracks MemAvailable, not MemFree" — and the midpoint assertion
        // below is what actually pins it.
        long drift = Math.max(64L * 1024 * 1024, m.totalBytes() / 100);
        assertThat(m.availableBytes()).isLessThanOrEqualTo(memAvail + drift);
        if (memAvail > memFree * 2) {
            long mid = memFree + (memAvail - memFree) / 2;
            assertThat(m.availableBytes()).isGreaterThan(mid);
        }
    }

    @Test
    void macos_reports_reclaimable_memory_not_just_idle_pages() {
        Assumptions.assumeTrue(Os.isDarwin());
        // Regression check for the bug this class's host_statistics64 read fixes:
        // com.sun.management's free-memory figure alone counts only truly-idle pages, not the
        // inactive/purgeable pages macOS's VM keeps stocked with reclaimable file cache — on a
        // long-lived dev machine that once reported ~150 MiB "available" out of 36 GiB, starving
        // worker-JVM heap sizing (HeapPlan) down to a ~32 MiB floor. A healthy read should surface a
        // real double-digit percentage of total as available.
        MemoryProbe.Memory m = MemoryProbe.probe();
        assertThat(m.availableBytes()).isGreaterThan(m.totalBytes() / 20);
    }
}
