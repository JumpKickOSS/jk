// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Windows command lines, which {@link ProcessHandle.Info} does not expose. */
public final class WindowsCommandLines {

    private WindowsCommandLines() {}

    /** Read one process's command line, or return an empty string when unavailable. */
    public static String of(long pid) {
        if (!Os.isWindows()) return "";
        NativeQuery api = NativeQuery.INSTANCE;
        if (api == null) return "";
        try (Arena arena = Arena.ofConfined()) {
            // CreateProcess accepts at most 32,767 UTF-16 code units, including the terminator.
            MemorySegment buffer = arena.allocate(16 + 32_768 * 2, ADDRESS.byteAlignment());
            return api.read(pid, buffer, arena.allocate(JAVA_INT));
        }
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
