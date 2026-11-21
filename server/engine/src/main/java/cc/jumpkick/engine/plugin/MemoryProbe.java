// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Container-aware host memory from the OS (not a JVM bean), so native and hosted jk agree.
 *
 * <p>Platform sources for {@link Memory#availableBytes()}:
 *
 * <ul>
 *   <li><b>Linux</b> — {@code /proc/meminfo} {@code MemAvailable} (same as {@code free -h}'s
 *       "available" column: free + reclaimable page cache), plus cgroup limits when present.
 *       Not {@code MemFree} / MXBean free, which under-report on aggressive file-cache hosts.
 *   <li><b>macOS</b> — {@code host_statistics64} reclaimable pages (free + inactive + purgeable;
 *       free already holds the speculative pages), not MXBean free alone.
 *   <li><b>else</b> (Windows, …) — {@code OperatingSystemMXBean} total/free physical memory.
 * </ul>
 *
 * Best-effort; falls back to {@link #FALLBACK_TOTAL}.
 */
public final class MemoryProbe {

    private MemoryProbe() {}

    /** Conservative last-resort total when nothing else can be read. */
    static final long FALLBACK_TOTAL = 2L << 30; // 2 GiB

    /** A cgroup limit at/above this is treated as "unlimited" (kernel sentinel). */
    static final long CGROUP_UNLIMITED = Long.MAX_VALUE / 2;

    /** The file system root the Linux files below are read under; a test points it at fixtures. */
    private static final Path HOST_ROOT = Path.of("/");

    private static final String PROC_MEMINFO = "proc/meminfo";
    private static final String CGROUP2_MAX = "sys/fs/cgroup/memory.max";
    private static final String CGROUP2_CURRENT = "sys/fs/cgroup/memory.current";
    private static final String CGROUP2_STAT = "sys/fs/cgroup/memory.stat";
    private static final String CGROUP1_LIMIT = "sys/fs/cgroup/memory/memory.limit_in_bytes";
    private static final String CGROUP1_USAGE = "sys/fs/cgroup/memory/memory.usage_in_bytes";

    /** {@code total} = physical/limit RAM; {@code available} = what we can still allocate. */
    public record Memory(long totalBytes, long availableBytes) {}

    /**
     * How long one reading answers {@link #probe}: long enough that one planning pass sizes every
     * worker from the same reading, short enough that the next build sees the memory the host has
     * then, not what it had when the engine started.
     */
    static final long TTL_NANOS = 5_000_000_000L;

    /** A reading and when it was taken; a pinned one is a test's and never expires. */
    private record Reading(Memory memory, long atNanos, boolean pinned) {}

    private static volatile @Nullable Reading cached;

    /** The host's memory as read in the last {@link #TTL_NANOS}, measured again once that has passed. */
    public static Memory probe() {
        return probeAt(Clock.SYSTEM.nanos());
    }

    /** As {@link #probe()}, judged at {@code nowNanos}. */
    static Memory probeAt(long nowNanos) {
        Reading r = cached;
        if (r != null && (r.pinned() || nowNanos - r.atNanos() < TTL_NANOS)) return r.memory();
        synchronized (MemoryProbe.class) {
            r = cached;
            if (r != null && (r.pinned() || nowNanos - r.atNanos() < TTL_NANOS)) return r.memory();
            Memory m = measure(HOST_ROOT);
            cached = new Reading(m, nowNanos, false);
            return m;
        }
    }

    /** Test seam: the memory {@link #probe} answers, or {@code null} to measure the host again. */
    public static void forTests(@Nullable Memory memory) {
        synchronized (MemoryProbe.class) {
            cached = memory == null ? null : new Reading(memory, 0, true);
        }
    }

    /** Test seam: a reading taken at {@code atNanos} that expires like a measured one. */
    static void readAtForTests(Memory memory, long atNanos) {
        synchronized (MemoryProbe.class) {
            cached = new Reading(memory, atNanos, false);
        }
    }

    /**
     * Live host memory (uncached) for status / SSE vitals. Prefer {@link #probe()} when sizing
     * worker heaps so one planning pass reads one reading.
     */
    public static Memory current() {
        return measure(HOST_ROOT);
    }

    /** This process's resident bytes, uncached; {@code -1} when the OS will not say. See {@link ProcessMemory}. */
    public static long ownRssBytes() {
        return ProcessMemory.self().residentBytes();
    }

    /** The memory read from {@code /proc} and {@code /sys} under {@code root}, uncached. */
    static Memory measure(Path root) {
        long hostTotal = -1;
        long hostAvail = -1;
        try {
            Path meminfoFile = root.resolve(PROC_MEMINFO);
            if (Files.isReadable(meminfoFile)) {
                String meminfo = Files.readString(meminfoFile);
                hostTotal = meminfoValueBytes(meminfo, "MemTotal");
                hostAvail = meminfoValueBytes(meminfo, "MemAvailable");
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the bean fallback below
            Log.debug("measure: fall through to the bean fallback below", e);
        }

        long cgLimit = readLong(root.resolve(CGROUP2_MAX));
        if (cgLimit < 0) cgLimit = readLong(root.resolve(CGROUP1_LIMIT));
        long cgUsage = readLong(root.resolve(CGROUP2_CURRENT));
        if (cgUsage < 0) cgUsage = readLong(root.resolve(CGROUP1_USAGE));

        long total = hostTotal;
        long available = hostAvail;

        // A real cgroup cap (smaller than the host) overrides both numbers: the
        // build can only use what the container allows.
        if (cgLimit > 0 && cgLimit < CGROUP_UNLIMITED && (total <= 0 || cgLimit < total)) {
            total = cgLimit;
            if (cgUsage >= 0) {
                long cgAvail = cgLimit - Math.max(0, cgUsage - inactiveFileBytes(readText(root.resolve(CGROUP2_STAT))));
                available = available > 0 ? Math.min(available, cgAvail) : cgAvail;
            }
        }

        if (total <= 0 || available <= 0) {
            Memory fallback = Os.isDarwin() ? fromMachHostStatistics64() : null;
            if (fallback == null) fallback = fromBean();
            if (total <= 0) total = fallback.totalBytes();
            if (available <= 0) available = fallback.availableBytes();
        }
        // Clamp so available never exceeds total and neither is non-positive.
        if (total <= 0) total = FALLBACK_TOTAL;
        if (available <= 0) available = total;
        if (available > total) available = total;
        return new Memory(total, available);
    }

    /** {@code mach/host_info.h}: the {@code HOST_VM_INFO64} flavor selector for {@code host_statistics64}. */
    private static final int HOST_VM_INFO64 = 4;

    /**
     * {@code HOST_VM_INFO64_REV1_COUNT}: the struct up to {@code swapped_count}, in {@code integer_t}s.
     * The kernel fills only as much as this asks for, so newer revisions' fields need no room.
     */
    private static final int HOST_VM_INFO64_COUNT = 38;

    /** {@code KERN_SUCCESS} in {@code mach/kern_return.h}. */
    private static final int KERN_SUCCESS = 0;

    // Byte offsets into vm_statistics64_data_t (mach/vm_statistics.h, declared aligned(8)) of the
    // fields we read: natural_t (4-byte) fields packed until a uint64_t field forces 8-byte alignment.
    static final long FREE_COUNT_OFFSET = 0;
    static final long INACTIVE_COUNT_OFFSET = 8;
    static final long PURGEABLE_COUNT_OFFSET = 88;
    static final long SPECULATIVE_COUNT_OFFSET = 92;

    private static final @Nullable Mac MAC = Os.isDarwin() ? Mac.load() : null;

    /**
     * macOS: reclaimable pages (free + inactive + purgeable) times the page size, matching how
     * Activity Monitor and other macOS tools report "available" memory — as opposed to {@code
     * com.sun.management}'s free-memory figure, which counts only truly-idle pages. Reads {@code
     * vm_statistics64_data_t} straight from the kernel via the same {@code host_statistics64} call
     * {@code vm_stat} itself makes — no subprocess, no text parsing. {@code null} on any failure
     * (missing symbol, non-zero {@code kern_return_t}, no native access), so the caller falls back
     * to {@link #fromBean()}.
     */
    private static @Nullable Memory fromMachHostStatistics64() {
        Mac m = MAC;
        if (m == null) return null;
        try {
            return m.read();
        } catch (Throwable t) {
            Log.debug("host memory: host_statistics64 failed", t);
            return null;
        }
    }

    /**
     * The host port, its page size and the {@code host_statistics64} handle, looked up once.
     * {@code mach_host_self} adds a send-right reference on every call, so the port is taken once
     * and held for the life of the process rather than per reading.
     */
    private record Mac(int hostPort, long pageSize, MethodHandle hostStatistics64) {

        static @Nullable Mac load() {
            try {
                Linker linker = Linker.nativeLinker();
                var lookup = linker.defaultLookup();
                MethodHandle machHostSelf = linker.downcallHandle(
                        lookup.find("mach_host_self").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
                MethodHandle hostPageSize = linker.downcallHandle(
                        lookup.find("host_page_size").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                MethodHandle hostStatistics64 = linker.downcallHandle(
                        lookup.find("host_statistics64").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                int hostPort = (int) machHostSelf.invokeExact();
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment pageSizeOut = arena.allocate(ValueLayout.JAVA_LONG);
                    if ((int) hostPageSize.invokeExact(hostPort, pageSizeOut) != KERN_SUCCESS) return null;
                    return new Mac(hostPort, pageSizeOut.get(ValueLayout.JAVA_LONG, 0), hostStatistics64);
                }
            } catch (Throwable unavailable) {
                Log.debug("host memory: host_statistics64 unavailable", unavailable);
                return null;
            }
        }

        @Nullable
        Memory read() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate((long) HOST_VM_INFO64_COUNT * Integer.BYTES, Long.BYTES);
                MemorySegment countInOut = arena.allocate(ValueLayout.JAVA_INT);
                countInOut.set(ValueLayout.JAVA_INT, 0, HOST_VM_INFO64_COUNT);
                if ((int) hostStatistics64.invokeExact(hostPort, HOST_VM_INFO64, info, countInOut) != KERN_SUCCESS) {
                    return null;
                }
                long available = availableBytes(info, pageSize);
                long total =
                        ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
                return available > 0 ? new Memory(total, available) : null;
            }
        }
    }

    /**
     * Free, inactive and purgeable pages of a {@code vm_statistics64_data_t}, in bytes. Not {@code
     * speculative_count}: those pages are already inside {@code free_count}.
     */
    static long availableBytes(MemorySegment info, long pageSize) {
        long free = pages(info, FREE_COUNT_OFFSET);
        long inactive = pages(info, INACTIVE_COUNT_OFFSET);
        long purgeable = pages(info, PURGEABLE_COUNT_OFFSET);
        return (free + inactive + purgeable) * pageSize;
    }

    /** {@code natural_t} (unsigned 32-bit) page count at {@code byteOffset} into a vm_statistics64_data_t. */
    private static long pages(MemorySegment info, long byteOffset) {
        return Integer.toUnsignedLong(info.get(ValueLayout.JAVA_INT, byteOffset));
    }

    /** {@code com.sun.management} OS bean fallback (Windows, or the Mach call unavailable). */
    private static Memory fromBean() {
        try {
            var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            return new Memory(total, free > 0 ? free : total);
        } catch (Throwable t) {
            // Bean unavailable (e.g. native-image without monitoring) — last resort.
            return new Memory(FALLBACK_TOTAL, FALLBACK_TOTAL);
        }
    }

    /** Parse a {@code /proc/meminfo} line ("MemTotal: 16004 kB") to bytes; {@code -1} if absent. */
    static long meminfoValueBytes(String meminfo, String key) {
        for (String line : meminfo.split("\n")) {
            if (!line.startsWith(key + ":")) continue;
            String rest = line.substring(key.length() + 1).trim(); // "16004 kB"
            String[] parts = rest.split("\\s+");
            try {
                long value = Long.parseLong(parts[0]);
                // meminfo is in kB unless a value carries no unit (rare); assume kB.
                return parts.length > 1 ? value * 1024 : value;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * {@code inactive_file} from a cgroup v2 {@code memory.stat} body, in bytes: file cache the kernel
     * reclaims before it kills anything. {@code 0} when absent.
     */
    static long inactiveFileBytes(@Nullable String stat) {
        if (stat == null) return 0;
        for (String line : stat.split("\n")) {
            if (!line.startsWith("inactive_file ")) continue;
            try {
                return Math.max(
                        0,
                        Long.parseLong(line.substring("inactive_file ".length()).trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static @Nullable String readText(Path file) {
        try {
            return Files.isReadable(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Read a single-number cgroup file; {@code -1} for absent/"max"/unparseable. */
    private static long readLong(Path file) {
        try {
            if (!Files.isReadable(file)) return -1;
            String s = Files.readString(file).trim();
            if (s.isEmpty() || s.equals("max")) return -1;
            return Long.parseLong(s);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
