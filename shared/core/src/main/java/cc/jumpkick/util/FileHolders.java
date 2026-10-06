// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The processes that hold a file open, as Windows' Restart Manager reports them: what a delete that
 * failed with a sharing violation can name instead of "another process". Empty on every other OS, and
 * whenever the Restart Manager cannot answer.
 */
public final class FileHolders {

    /** One process holding a file: its pid and the name Windows gives the application. */
    public record Holder(long pid, String appName) {}

    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_MORE_DATA = 234;

    /** {@code CCH_RM_SESSION_KEY + 1} UTF-16 units. */
    private static final int SESSION_KEY_CHARS = 33;

    /** {@code sizeof(RM_PROCESS_INFO)}: a 12-byte RM_UNIQUE_PROCESS, two names, four 32-bit fields. */
    private static final int PROCESS_INFO_SIZE = 12 + 256 * 2 + 64 * 2 + 4 * 4;

    private static final int APP_NAME_OFFSET = 12;
    private static final int APP_NAME_CHARS = 256;

    /** Holders asked for in one call; more than this is reported as the first ones. */
    private static final int MAX_HOLDERS = 16;

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private FileHolders() {}

    /** The processes holding {@code file} open; empty when none, off Windows, or on any failure. */
    public static List<Holder> of(Path file) {
        if (!Os.isWindows()) return List.of();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment session = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment key = arena.allocate((long) SESSION_KEY_CHARS * 2);
            if ((int) RestartManager.START_SESSION.invokeExact(session, 0, key) != ERROR_SUCCESS) return List.of();
            int handle = session.get(ValueLayout.JAVA_INT, 0);
            try {
                MemorySegment names = arena.allocate(ValueLayout.ADDRESS);
                names.set(
                        ValueLayout.ADDRESS,
                        0,
                        arena.allocateFrom(file.toAbsolutePath().toString(), StandardCharsets.UTF_16LE));
                int registered = (int) RestartManager.REGISTER_RESOURCES.invokeExact(
                        handle, 1, names, 0, MemorySegment.NULL, 0, MemorySegment.NULL);
                if (registered != ERROR_SUCCESS) return List.of();
                MemorySegment needed = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
                count.set(ValueLayout.JAVA_INT, 0, MAX_HOLDERS);
                MemorySegment infos = arena.allocate((long) PROCESS_INFO_SIZE * MAX_HOLDERS, 8);
                MemorySegment reasons = arena.allocate(ValueLayout.JAVA_INT);
                int listed = (int) RestartManager.GET_LIST.invokeExact(handle, needed, count, infos, reasons);
                if (listed != ERROR_SUCCESS && listed != ERROR_MORE_DATA) return List.of();
                int n = Math.min(count.get(ValueLayout.JAVA_INT, 0), MAX_HOLDERS);
                List<Holder> holders = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    MemorySegment info = infos.asSlice((long) i * PROCESS_INFO_SIZE, PROCESS_INFO_SIZE);
                    long pid = Integer.toUnsignedLong(info.get(U32, 0));
                    holders.add(new Holder(pid, utf16(info.asSlice(APP_NAME_OFFSET, APP_NAME_CHARS * 2L))));
                }
                return List.copyOf(holders);
            } finally {
                int ended = (int) RestartManager.END_SESSION.invokeExact(handle);
            }
        } catch (Throwable unavailable) {
            return List.of();
        }
    }

    /** A NUL-terminated UTF-16LE string at the start of {@code chars}. */
    private static String utf16(MemorySegment chars) {
        byte[] bytes = chars.toArray(ValueLayout.JAVA_BYTE);
        int end = 0;
        while (end + 1 < bytes.length && (bytes[end] != 0 || bytes[end + 1] != 0)) end += 2;
        return new String(bytes, 0, end, StandardCharsets.UTF_16LE);
    }

    /** rstrtmgr downcalls, bound on first use so a POSIX host never looks them up. */
    private static final class RestartManager {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup RSTRTMGR = SymbolLookup.libraryLookup("rstrtmgr", Arena.global());

        static final MethodHandle START_SESSION = LINKER.downcallHandle(
                RSTRTMGR.findOrThrow("RmStartSession"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        static final MethodHandle REGISTER_RESOURCES = LINKER.downcallHandle(
                RSTRTMGR.findOrThrow("RmRegisterResources"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

        static final MethodHandle GET_LIST = LINKER.downcallHandle(
                RSTRTMGR.findOrThrow("RmGetList"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));

        static final MethodHandle END_SESSION = LINKER.downcallHandle(
                RSTRTMGR.findOrThrow("RmEndSession"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }
}
