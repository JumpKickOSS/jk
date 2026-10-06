// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows directory junctions, made through the Win32 API: unlike a symbolic link a junction needs no
 * privilege, and it may point at any local volume. A junction is a directory carrying a mount-point
 * reparse buffer ({@code FSCTL_SET_REPARSE_POINT}); {@link PathUtil}'s delete treats it as a leaf.
 */
public final class Junctions {

    private static final int GENERIC_WRITE = 0x40000000;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FSCTL_SET_REPARSE_POINT = 0x000900A4;
    private static final int IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003;
    private static final long INVALID_HANDLE_VALUE = -1L;

    /** The fixed part of a mount-point REPARSE_DATA_BUFFER before its path buffer, in bytes. */
    private static final int HEADER = 16;

    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private Junctions() {}

    /**
     * Make {@code link} a junction to the directory {@code target}. {@code link} must not exist; its
     * parent must. Windows only.
     */
    public static void create(Path link, Path target) throws IOException {
        if (!Os.isWindows()) throw new UnsupportedOperationException("junctions are a Windows feature");
        Path to = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(to)) throw new IOException("junction target is not a directory: " + to);
        Files.createDirectory(link);
        try {
            setMountPoint(link.toAbsolutePath(), to.toString());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(link);
            throw e;
        }
    }

    private static void setMountPoint(Path dir, String target) throws IOException {
        // The substitute name is the NT path; the print name is what a directory listing shows.
        byte[] substitute = ("\\??\\" + target).getBytes(StandardCharsets.UTF_16LE);
        byte[] print = target.getBytes(StandardCharsets.UTF_16LE);
        int pathBuffer = substitute.length + 2 + print.length + 2;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(HEADER + pathBuffer);
            buf.set(U32, 0, IO_REPARSE_TAG_MOUNT_POINT);
            buf.set(U16, 4, (short) (8 + pathBuffer)); // ReparseDataLength: the offsets plus the buffer
            buf.set(U16, 8, (short) 0); // SubstituteNameOffset
            buf.set(U16, 10, (short) substitute.length);
            buf.set(U16, 12, (short) (substitute.length + 2)); // PrintNameOffset, past the NUL
            buf.set(U16, 14, (short) print.length);
            MemorySegment.copy(substitute, 0, buf, ValueLayout.JAVA_BYTE, HEADER, substitute.length);
            MemorySegment.copy(print, 0, buf, ValueLayout.JAVA_BYTE, HEADER + substitute.length + 2, print.length);

            MemorySegment state = arena.allocate(Win32.CALL_STATE);
            MemorySegment handle = (MemorySegment) Win32.CREATE_FILE.invokeExact(
                    state,
                    arena.allocateFrom(dir.toString(), StandardCharsets.UTF_16LE),
                    GENERIC_WRITE,
                    0,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                    MemorySegment.NULL);
            if (handle.address() == INVALID_HANDLE_VALUE || handle.address() == 0) {
                throw new IOException("cannot open " + dir + " to make it a junction (Windows error "
                        + (int) Win32.LAST_ERROR.get(state, 0L) + ")");
            }
            try {
                MemorySegment returned = arena.allocate(ValueLayout.JAVA_INT);
                int ok = (int) Win32.DEVICE_IO_CONTROL.invokeExact(
                        state,
                        handle,
                        FSCTL_SET_REPARSE_POINT,
                        buf,
                        (int) buf.byteSize(),
                        MemorySegment.NULL,
                        0,
                        returned,
                        MemorySegment.NULL);
                if (ok == 0) {
                    throw new IOException("cannot make " + dir + " a junction to " + target + " (Windows error "
                            + (int) Win32.LAST_ERROR.get(state, 0L) + ")");
                }
            } finally {
                int closed = (int) Win32.CLOSE_HANDLE.invokeExact(handle);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("cannot make " + dir + " a junction to " + target, t);
        }
    }

    /** kernel32 downcalls, bound on first use so a POSIX host never looks them up. */
    private static final class Win32 {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final Linker.Option LAST_ERROR_OPTION = Linker.Option.captureCallState("GetLastError");
        static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        static final VarHandle LAST_ERROR = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

        static final MethodHandle CREATE_FILE = LINKER.downcallHandle(
                KERNEL32.findOrThrow("CreateFileW"),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS),
                LAST_ERROR_OPTION);

        static final MethodHandle DEVICE_IO_CONTROL = LINKER.downcallHandle(
                KERNEL32.findOrThrow("DeviceIoControl"),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS),
                LAST_ERROR_OPTION);

        static final MethodHandle CLOSE_HANDLE = LINKER.downcallHandle(
                KERNEL32.findOrThrow("CloseHandle"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }
}
