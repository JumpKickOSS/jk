// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Windows command lines, which {@link ProcessHandle.Info} does not expose. */
public final class WindowsCommandLines {

    /** One query serves a whole command: {@code jk engine status} enumerates the fleet three times. */
    private static volatile @Nullable Snapshot snapshot;

    /**
     * The command lines of every process the query saw. {@code takenAt} is read before the query
     * runs, so a process that started during it still counts as newer; the TTL runs from
     * {@code answeredNanos}, so a slow query does not hand back a snapshot already expired.
     */
    private record Snapshot(Map<Long, String> rows, long answeredNanos, Instant takenAt) {}

    /**
     * How long a snapshot stands. Every caller today is a short-lived CLI command, so this only
     * ever coalesces one command's repeated enumerations; a process that started after the snapshot
     * re-queries regardless, so a long-lived caller never misses a new process.
     */
    private static final long SNAPSHOT_TTL_NANOS = 5_000_000_000L;

    private WindowsCommandLines() {}

    /**
     * This process's command line, or {@code ""} — off Windows, unknown, or the query failed. A pid
     * that started after the snapshot is looked up again.
     */
    public static String of(long pid) {
        return new Scan().of(pid);
    }

    /**
     * Lookups over the whole process table: the first process newer than the snapshot re-queries
     * and later ones read that answer, so a busy machine's new processes cannot chain query after
     * query.
     */
    static class Scan {
        private boolean requeried;

        /** As {@link WindowsCommandLines#of}, re-querying at most once for this scan. */
        String of(long pid) {
            if (!Os.isWindows()) return "";
            Snapshot current = current();
            String cmd = current.rows().get(pid);
            if (cmd != null) return cmd;
            if (requeried || !startedAfter(pid, current.takenAt())) return "";
            requeried = true;
            return refresh(current).rows().getOrDefault(pid, "");
        }
    }

    private static Snapshot current() {
        Snapshot local = snapshot;
        if (local != null && Clock.SYSTEM.nanos() - local.answeredNanos() < SNAPSHOT_TTL_NANOS) return local;
        return refresh(local);
    }

    /** Re-query unless another thread already replaced {@code stale}. */
    private static Snapshot refresh(@Nullable Snapshot stale) {
        synchronized (WindowsCommandLines.class) {
            Snapshot local = snapshot;
            if (local == null || local == stale) {
                Instant at = Clock.SYSTEM.instant();
                Map<Long, String> rows = query();
                local = new Snapshot(rows, Clock.SYSTEM.nanos(), at);
                snapshot = local;
            }
            return local;
        }
    }

    /** Whether {@code pid} is a live process that started after {@code instant}. */
    private static boolean startedAfter(long pid, Instant instant) {
        return ProcessHandle.of(pid)
                .flatMap(h -> h.info().startInstant())
                .map(started -> !started.isBefore(instant))
                .orElse(false);
    }

    private static Map<Long, String> query() {
        NativeQuery api = NativeQuery.INSTANCE;
        if (api == null) return Map.of();
        Map<Long, String> rows = new HashMap<>();
        try (Arena arena = Arena.ofConfined();
                var processes = ProcessHandle.allProcesses()) {
            // CreateProcess accepts at most 32,767 UTF-16 code units, including the terminator.
            MemorySegment buffer = arena.allocate(16 + 32_768 * 2, ADDRESS.byteAlignment());
            MemorySegment returned = arena.allocate(JAVA_INT);
            processes.forEach(process -> {
                String command = api.read(process.pid(), buffer, returned);
                if (!command.isEmpty()) rows.put(process.pid(), command);
            });
        }
        return Map.copyOf(rows);
    }

    /** Decode the counted UNICODE_STRING returned inside the query buffer. */
    static String decode(MemorySegment buffer) {
        long pointerOffset = ADDRESS.byteAlignment();
        if (buffer.byteSize() < pointerOffset + ADDRESS.byteSize()) return "";
        int length = Short.toUnsignedInt(buffer.get(JAVA_SHORT, 0));
        long offset = buffer.get(ADDRESS, pointerOffset).address() - buffer.address();
        if (length == 0
                || (length & 1) != 0
                || offset < pointerOffset + ADDRESS.byteSize()
                || offset > buffer.byteSize() - length) return "";
        return new String(buffer.asSlice(offset, length).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
    }

    private record NativeQuery(MethodHandle open, MethodHandle query, MethodHandle close) {
        private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        private static final int PROCESS_COMMAND_LINE_INFORMATION = 60;
        private static final @Nullable NativeQuery INSTANCE = bind();

        private static @Nullable NativeQuery bind() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
                SymbolLookup nt = SymbolLookup.libraryLookup("ntdll", Arena.global());
                return new NativeQuery(
                        linker.downcallHandle(
                                kernel.findOrThrow("OpenProcess"),
                                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
                        linker.downcallHandle(
                                nt.findOrThrow("NtQueryInformationProcess"),
                                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)),
                        linker.downcallHandle(
                                kernel.findOrThrow("CloseHandle"), FunctionDescriptor.of(JAVA_INT, ADDRESS)));
            } catch (RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        String read(long pid, MemorySegment buffer, MemorySegment returned) {
            try {
                MemorySegment process =
                        (MemorySegment) open.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
                if (process.equals(MemorySegment.NULL)) return "";
                try {
                    returned.set(JAVA_INT, 0, 0);
                    int status = (int) query.invokeExact(
                            process, PROCESS_COMMAND_LINE_INFORMATION, buffer, (int) buffer.byteSize(), returned);
                    int bytes = returned.get(JAVA_INT, 0);
                    return status == 0 && bytes > 0 && bytes <= buffer.byteSize()
                            ? decode(buffer.asSlice(0, bytes))
                            : "";
                } finally {
                    int ignored = (int) close.invokeExact(process);
                }
            } catch (Throwable unavailable) {
                return "";
            }
        }
    }

    /**
     * Whether {@code command} (an executable path from {@code ProcessHandle.info().command()}) is a
     * JVM launcher. The weakest corroboration this class's callers use, and only where jk's own
     * state already named the pid.
     */
    static boolean isJvmExecutable(String command) {
        if (command == null || command.isBlank()) return false;
        String name = command.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }
}
