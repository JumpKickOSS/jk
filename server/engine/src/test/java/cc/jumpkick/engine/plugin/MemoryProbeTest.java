// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void linux_available_is_memavailable_not_memfree(@TempDir Path root) throws IOException {
        write(root, "proc/meminfo", MEMINFO);

        assertThat(MemoryProbe.measure(root))
                .as("free + reclaimable cache, not idle pages alone")
                .isEqualTo(new MemoryProbe.Memory(16004000L * 1024, 8000000L * 1024));
    }

    @Test
    void a_cgroup_limit_below_the_host_caps_total_and_available(@TempDir Path root) throws IOException {
        write(root, "proc/meminfo", MEMINFO);
        write(root, "sys/fs/cgroup/memory.max", String.valueOf(4L << 30));
        write(root, "sys/fs/cgroup/memory.current", String.valueOf(2L << 30));
        write(root, "sys/fs/cgroup/memory.stat", "anon 1000\ninactive_file " + (1L << 30) + "\nactive_file 5\n");

        assertThat(MemoryProbe.measure(root))
                .as("the limit less what the box uses, inactive file cache counted as reclaimable")
                .isEqualTo(new MemoryProbe.Memory(4L << 30, 3L << 30));
    }

    @Test
    void a_cgroup_with_room_to_spare_still_answers_the_hosts_memavailable(@TempDir Path root) throws IOException {
        write(root, "proc/meminfo", MEMINFO);
        write(root, "sys/fs/cgroup/memory.max", String.valueOf(12L << 30));
        write(root, "sys/fs/cgroup/memory.current", String.valueOf(1L << 30));

        assertThat(MemoryProbe.measure(root)).isEqualTo(new MemoryProbe.Memory(12L << 30, 8000000L * 1024));
    }

    @Test
    void an_unlimited_cgroup_leaves_the_host_numbers_alone(@TempDir Path root) throws IOException {
        write(root, "proc/meminfo", MEMINFO);
        write(root, "sys/fs/cgroup/memory.max", "max\n");
        write(root, "sys/fs/cgroup/memory.current", String.valueOf(15L << 30));

        assertThat(MemoryProbe.measure(root)).isEqualTo(new MemoryProbe.Memory(16004000L * 1024, 8000000L * 1024));
    }

    private static void write(Path root, String relative, String body) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
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

    @Test
    void a_reading_answers_for_its_ttl_and_the_host_is_read_again_after_it() {
        MemoryProbe.Memory stale = new MemoryProbe.Memory(1, 1);
        long at = 1_000_000_000L;
        try {
            MemoryProbe.readAtForTests(stale, at);
            assertThat(MemoryProbe.probeAt(at + 1))
                    .as("one planning pass, one reading")
                    .isEqualTo(stale);

            assertThat(MemoryProbe.probeAt(at + MemoryProbe.TTL_NANOS))
                    .as("the next build reads the host, not the engine-start reading")
                    .isNotEqualTo(stale);
        } finally {
            MemoryProbe.forTests(null);
        }
    }

    @Test
    void speculative_pages_are_counted_once_as_part_of_free() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(38 * Integer.BYTES, 8);
            info.set(ValueLayout.JAVA_INT, MemoryProbe.FREE_COUNT_OFFSET, 100);
            info.set(ValueLayout.JAVA_INT, MemoryProbe.SPECULATIVE_COUNT_OFFSET, 40);
            info.set(ValueLayout.JAVA_INT, MemoryProbe.INACTIVE_COUNT_OFFSET, 300);
            info.set(ValueLayout.JAVA_INT, MemoryProbe.PURGEABLE_COUNT_OFFSET, 7);
            // The kernel's free_count already holds the speculative pages; vm_stat prints
            // free_count - speculative_count as "Pages free" for that reason.
            assertThat(MemoryProbe.availableBytes(info, 16384)).isEqualTo((100L + 300 + 7) * 16384);
        }
    }

    @Test
    void reading_macos_memory_takes_no_lasting_reference_to_the_host_port() throws Throwable {
        Assumptions.assumeTrue(Os.isDarwin());
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        MethodHandle hostSelf = linker.downcallHandle(
                libc.find("mach_host_self").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle getRefs = linker.downcallHandle(
                libc.find("mach_port_get_refs").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));
        MethodHandle deallocate = linker.downcallHandle(
                libc.find("mach_port_deallocate").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int task = libc.find("mach_task_self_")
                .orElseThrow()
                .reinterpret(Integer.BYTES)
                .get(ValueLayout.JAVA_INT, 0);
        int sendRight = 0; // MACH_PORT_RIGHT_SEND

        MemoryProbe.current(); // the first reading may set up what later ones reuse
        int host = (int) hostSelf.invokeExact();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment refs = arena.allocate(ValueLayout.JAVA_INT);
            assertThat((int) getRefs.invokeExact(task, host, sendRight, refs)).isZero();
            int before = refs.get(ValueLayout.JAVA_INT, 0);
            int readings = 10_000;
            for (int i = 0; i < readings; i++) MemoryProbe.current();
            assertThat((int) getRefs.invokeExact(task, host, sendRight, refs)).isZero();
            // The JVM takes references of its own while the readings run (the JIT asks for free
            // memory the same way), so the bound is far under one reference per reading, not zero.
            assertThat(refs.get(ValueLayout.JAVA_INT, 0) - before)
                    .as("send-right references on the host port gained over " + readings + " readings")
                    .isLessThan(readings / 10);
        } finally {
            int ignored = (int) deallocate.invokeExact(task, host);
        }
    }
}
