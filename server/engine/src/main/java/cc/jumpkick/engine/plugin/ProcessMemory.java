// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
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
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A process's resident memory now and at its peak, read from the OS on Linux, Windows and macOS.
 *
 * <ul>
 *   <li><b>Linux</b>: {@code VmRSS} and {@code VmHWM} from {@code /proc/<pid>/status}.
 *   <li><b>Windows</b>: {@code WorkingSetSize} and {@code PeakWorkingSetSize} from {@code
 *       GetProcessMemoryInfo}.
 *   <li><b>macOS</b>: {@code ri_phys_footprint} and {@code ri_lifetime_max_phys_footprint} from
 *       {@code proc_pid_rusage}, the figure Activity Monitor shows as a process's memory.
 * </ul>
 *
 * @see <a href="https://learn.microsoft.com/windows/win32/api/psapi/nf-psapi-getprocessmemoryinfo">GetProcessMemoryInfo</a>
 */
public final class ProcessMemory {

    /** Resident bytes now and the most the process has held, both {@code -1} when unread. */
    public record Usage(long residentBytes, long peakBytes) {
        static final Usage UNKNOWN = new Usage(-1, -1);

        public boolean known() {
            return residentBytes > 0 || peakBytes > 0;
        }
    }

    private ProcessMemory() {}

    private static final @Nullable Windows WINDOWS = Os.isWindows() ? Windows.load() : null;
    private static final @Nullable Mac MAC = Os.isDarwin() ? Mac.load() : null;

    /** Process {@code pid}'s memory; {@link Usage#UNKNOWN} when it is gone or the OS will not say. */
    public static Usage of(long pid) {
        if (pid <= 0) return Usage.UNKNOWN;
        try {
            if (Os.isLinux()) return linux(Path.of("/proc", Long.toString(pid), "status"));
            Windows w = WINDOWS;
            if (w != null) return w.read(pid);
            Mac m = MAC;
            if (m != null) return m.read((int) pid);
        } catch (Throwable t) {
            Log.debug("process memory of " + pid + ": best-effort", t);
        }
        return Usage.UNKNOWN;
    }

    /**
     * Readings of process trees: a root and its live descendants, their resident bytes summed and
     * their own peaks summed. A native-image launcher's builder JVM is its child. Finding the
     * descendants walks the whole process table, about 10 ms on Windows against about 1 µs for one
     * process's reading, so each root's descendants are looked up again at most every {@link
     * #CHILDREN_EVERY_NANOS}.
     */
    public static final class Trees implements WorkerRss.Source {

        /** How long a root's descendants are reused before the process table is walked again. */
        static final long CHILDREN_EVERY_NANOS = 15_000_000_000L;

        /** Roots remembered at most; past it the cache starts over. */
        private static final int MAX_ROOTS = 512;

        private record Known(long[] children, long checkedAt) {}

        private final ConcurrentHashMap<Long, Known> known = new ConcurrentHashMap<>();

        /** {@code pid}'s tree; {@link Usage#UNKNOWN} when the root is gone or the OS will not say. */
        public Usage read(long pid) {
            Usage root = of(pid);
            if (!root.known()) {
                known.remove(pid);
                return root;
            }
            long now = Clock.SYSTEM.nanos();
            Known k = known.get(pid);
            if (k == null || now - k.checkedAt() >= CHILDREN_EVERY_NANOS) {
                if (known.size() >= MAX_ROOTS) known.clear();
                k = new Known(descendants(pid), now);
                known.put(pid, k);
            }
            long resident = Math.max(0, root.residentBytes());
            long peak = Math.max(0, root.peakBytes());
            for (long child : k.children()) {
                Usage u = of(child);
                resident += Math.max(0, u.residentBytes());
                peak += Math.max(0, u.peakBytes());
            }
            return new Usage(resident, Math.max(peak, resident));
        }

        @Override
        public long rssBytes(long pid) {
            return read(pid).residentBytes();
        }

        private static long[] descendants(long pid) {
            try {
                return ProcessHandle.of(pid)
                        .map(h -> h.descendants().mapToLong(ProcessHandle::pid).toArray())
                        .orElse(new long[0]);
            } catch (RuntimeException e) {
                return new long[0];
            }
        }
    }

    /** This process's memory. */
    public static Usage self() {
        return of(ProcessHandle.current().pid());
    }

    private static Usage linux(Path status) throws IOException {
        if (!Files.isReadable(status)) return Usage.UNKNOWN;
        String body = Files.readString(status);
        return new Usage(MemoryProbe.meminfoValueBytes(body, "VmRSS"), MemoryProbe.meminfoValueBytes(body, "VmHWM"));
    }

    /** {@code kernel32}'s {@code OpenProcess}, {@code K32GetProcessMemoryInfo} and {@code CloseHandle}. */
    private record Windows(MethodHandle open, MethodHandle info, MethodHandle close) {

        private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

        /** {@code PROCESS_MEMORY_COUNTERS} on a 64-bit process: a DWORD pair, then eight SIZE_Ts. */
        private static final int COUNTERS_BYTES = 72;

        private static final long PEAK_WORKING_SET = 8;
        private static final long WORKING_SET = 16;

        static @Nullable Windows load() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                return new Windows(
                        linker.downcallHandle(
                                kernel32.find("OpenProcess").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                kernel32.find("K32GetProcessMemoryInfo").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                kernel32.find("CloseHandle").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
            } catch (Throwable unavailable) {
                Log.debug("process memory: kernel32 calls unavailable", unavailable);
                return null;
            }
        }

        Usage read(long pid) throws Throwable {
            MemorySegment handle = (MemorySegment) open.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
            if (handle.address() == 0) return Usage.UNKNOWN;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment counters = arena.allocate(COUNTERS_BYTES, 8);
                counters.set(ValueLayout.JAVA_INT, 0, COUNTERS_BYTES);
                if ((int) info.invokeExact(handle, counters, COUNTERS_BYTES) == 0) return Usage.UNKNOWN;
                return new Usage(
                        counters.get(ValueLayout.JAVA_LONG, WORKING_SET),
                        counters.get(ValueLayout.JAVA_LONG, PEAK_WORKING_SET));
            } finally {
                int closed = (int) close.invokeExact(handle);
                if (closed == 0) Log.debug("process memory: CloseHandle failed");
            }
        }
    }

    /** {@code libproc}'s {@code proc_pid_rusage} with the {@code rusage_info_v4} flavor. */
    private record Mac(MethodHandle rusage) {

        private static final int RUSAGE_INFO_V4 = 4;
        private static final int V4_BYTES = 296;
        private static final long PHYS_FOOTPRINT = 72;
        private static final long LIFETIME_MAX_PHYS_FOOTPRINT = 240;

        static @Nullable Mac load() {
            try {
                Linker linker = Linker.nativeLinker();
                return new Mac(linker.downcallHandle(
                        linker.defaultLookup().find("proc_pid_rusage").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS)));
            } catch (Throwable unavailable) {
                Log.debug("process memory: proc_pid_rusage unavailable", unavailable);
                return null;
            }
        }

        Usage read(int pid) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(V4_BYTES, 8);
                if ((int) rusage.invokeExact(pid, RUSAGE_INFO_V4, info) != 0) return Usage.UNKNOWN;
                return new Usage(
                        info.get(ValueLayout.JAVA_LONG, PHYS_FOOTPRINT),
                        info.get(ValueLayout.JAVA_LONG, LIFETIME_MAX_PHYS_FOOTPRINT));
            }
        }
    }
}
