// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A file's identity and hard-link count on Windows, which no Java file attribute view reports:
 * NTFS keeps both ({@code dwVolumeSerialNumber}, {@code nFileIndexHigh/Low} and {@code
 * nNumberOfLinks} in {@code BY_HANDLE_FILE_INFORMATION}), and {@code fileKey()} is null there. Read
 * through {@code GetFileInformationByHandle} on a handle opened for attributes only, through FFM.
 *
 * <p>Anything that goes wrong (not Windows, a downcall the native image did not register, a file
 * that will not open) answers {@code null}, and the caller treats the file as one it owns, as it
 * did before link counts were read.
 */
final class WindowsFileIdentity {

    /** The volume and file index naming one file, and how many directory entries link to it. */
    record Info(long volume, long index, int links) {}

    private static final int FILE_READ_ATTRIBUTES = 0x80;
    private static final int FILE_SHARE_ALL = 0x1 | 0x2 | 0x4;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;

    /** {@code BY_HANDLE_FILE_INFORMATION}: 52 bytes, DWORD-aligned. */
    static final int INFO_BYTES = 52;

    static final int OFF_VOLUME = 28;
    static final int OFF_LINKS = 40;
    static final int OFF_INDEX_HIGH = 44;
    static final int OFF_INDEX_LOW = 48;

    private static volatile boolean initAttempted;
    private static volatile @Nullable MethodHandle createFileW;
    private static volatile @Nullable MethodHandle getFileInformationByHandle;
    private static volatile @Nullable MethodHandle closeHandle;

    private WindowsFileIdentity() {}

    /** {@code path}'s identity and link count, or null when it cannot be read here. */
    static @Nullable Info read(Path path) {
        if (!Os.isWindows()) return null;
        ensure();
        MethodHandle open = createFileW;
        MethodHandle info = getFileInformationByHandle;
        MethodHandle close = closeHandle;
        if (open == null || info == null || close == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name =
                    arena.allocateFrom(ValueLayout.JAVA_CHAR, (path.toAbsolutePath() + "\0").toCharArray());
            MemorySegment handle = (MemorySegment) open.invokeExact(
                    name,
                    FILE_READ_ATTRIBUTES,
                    FILE_SHARE_ALL,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_FLAG_BACKUP_SEMANTICS,
                    MemorySegment.NULL);
            if (handle.address() == -1L || handle.address() == 0L) return null;
            try {
                MemorySegment out = arena.allocate(INFO_BYTES, 4);
                int ok = (int) info.invokeExact(handle, out);
                return ok == 0 ? null : decode(out);
            } finally {
                int ignored = (int) close.invokeExact(handle);
            }
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** The fields of a {@code BY_HANDLE_FILE_INFORMATION} the caller needs. */
    static Info decode(MemorySegment out) {
        long volume = Integer.toUnsignedLong(out.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_VOLUME));
        long high = Integer.toUnsignedLong(out.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_INDEX_HIGH));
        long low = Integer.toUnsignedLong(out.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_INDEX_LOW));
        int links = out.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_LINKS);
        return new Info(volume, (high << 32) | low, links);
    }

    private static synchronized void ensure() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            createFileW = linker.downcallHandle(
                    k32.findOrThrow("CreateFileW"),
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            getFileInformationByHandle = linker.downcallHandle(
                    k32.findOrThrow("GetFileInformationByHandle"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            closeHandle = linker.downcallHandle(
                    k32.findOrThrow("CloseHandle"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        } catch (Throwable unavailable) {
            createFileW = null;
            getFileInformationByHandle = null;
            closeHandle = null;
        }
    }
}
