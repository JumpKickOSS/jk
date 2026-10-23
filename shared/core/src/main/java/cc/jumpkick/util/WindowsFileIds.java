// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.StatIdentity;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A Windows file's {@code ChangeTime} and its volume serial plus file id, which the JDK's
 * attributes do not carry: one {@code CreateFileW} for attribute access, {@code
 * GetFileInformationByHandleEx} for {@code FILE_BASIC_INFO} and {@code FILE_ID_INFO}, then {@code
 * CloseHandle}. A volume that does not report a 128-bit id falls back to {@code
 * BY_HANDLE_FILE_INFORMATION}'s 64-bit index. {@link StatIdentity} loads it by name on Windows,
 * since the host module it lives beside targets a release without the foreign-function API.
 */
public final class WindowsFileIds implements StatIdentity.WindowsReader {

    private static final int FILE_READ_ATTRIBUTES = 0x80;
    private static final int FILE_SHARE_ALL = 0x1 | 0x2 | 0x4;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_BASIC_INFO = 0;
    private static final int FILE_ID_INFO = 18;

    /** {@code FileStatBasicByNameInfo}: {@code FILE_STAT_BASIC_INFORMATION}, 104 bytes. */
    private static final int FILE_STAT_BASIC_BY_NAME = 3;

    /** 100 ns ticks between 1601-01-01 and 1970-01-01. */
    private static final long EPOCH_TICKS = 116_444_736_000_000_000L;

    private static final @Nullable Native NATIVE = Native.load();

    public WindowsFileIds() {}

    /**
     * One {@code GetFileInformationByName} (Windows 11 24H2 and later): size, write and change
     * times, volume serial and 128-bit file id together, the same values the handle path below
     * reads, without opening the file. Null where the call is missing or fails, and for a reparse
     * point, whose link-following the handle path decides.
     */
    @Override
    public @Nullable StatIdentity stat(Path file) {
        Native n = NATIVE;
        if (n == null || n.byName == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(file.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            MemorySegment info = arena.allocate(104, 8);
            if ((int) n.byName.invokeExact(name, FILE_STAT_BASIC_BY_NAME, info, (int) info.byteSize()) == 0)
                return null;
            if (info.get(ValueLayout.JAVA_INT, 60) != 0) return null;
            long size = info.get(ValueLayout.JAVA_LONG, 48);
            long mtime = (info.get(ValueLayout.JAVA_LONG, 24) - EPOCH_TICKS) * 100L;
            long ctime = (info.get(ValueLayout.JAVA_LONG, 32) - EPOCH_TICKS) * 100L;
            String key = Long.toHexString(info.get(ValueLayout.JAVA_LONG, 80)) + ":"
                    + Long.toHexString(info.get(ValueLayout.JAVA_LONG, 96))
                    + Long.toHexString(info.get(ValueLayout.JAVA_LONG, 88));
            return new StatIdentity(size, mtime, ctime, key);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** The ids of {@code file}, or {@code null} when the platform calls are unavailable or fail. */
    @Override
    public StatIdentity.@Nullable WindowsIds read(Path file, boolean noFollow) {
        Native n = NATIVE;
        if (n == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(file.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            int flags = FILE_FLAG_BACKUP_SEMANTICS | (noFollow ? FILE_FLAG_OPEN_REPARSE_POINT : 0);
            MemorySegment handle = (MemorySegment) n.createFile.invokeExact(
                    name,
                    FILE_READ_ATTRIBUTES,
                    FILE_SHARE_ALL,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    flags,
                    MemorySegment.NULL);
            if (handle.address() == -1L || handle.address() == 0L) return null;
            try {
                MemorySegment basic = arena.allocate(40, 8);
                if ((int) n.getInfo.invokeExact(handle, FILE_BASIC_INFO, basic, (int) basic.byteSize()) == 0)
                    return null;
                long changeTicks = basic.get(ValueLayout.JAVA_LONG, 24);
                long changeNanos = (changeTicks - EPOCH_TICKS) * 100L;
                MemorySegment id = arena.allocate(24, 8);
                String key;
                if ((int) n.getInfo.invokeExact(handle, FILE_ID_INFO, id, (int) id.byteSize()) != 0) {
                    key = Long.toHexString(id.get(ValueLayout.JAVA_LONG, 0)) + ":"
                            + Long.toHexString(id.get(ValueLayout.JAVA_LONG, 16))
                            + Long.toHexString(id.get(ValueLayout.JAVA_LONG, 8));
                } else {
                    MemorySegment byHandle = arena.allocate(52, 8);
                    if ((int) n.getByHandle.invokeExact(handle, byHandle) == 0) return null;
                    key = Integer.toHexString(byHandle.get(ValueLayout.JAVA_INT_UNALIGNED, 28)) + ":"
                            + Integer.toHexString(byHandle.get(ValueLayout.JAVA_INT_UNALIGNED, 44))
                            + Integer.toHexString(byHandle.get(ValueLayout.JAVA_INT_UNALIGNED, 48));
                }
                return new StatIdentity.WindowsIds(changeNanos, key);
            } finally {
                int ignored = (int) n.closeHandle.invokeExact(handle);
            }
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * The bound kernel32 entry points; absent off Windows, or in a JVM started without {@code
     * --enable-native-access}, where binding them would print the JDK's restricted-method warning
     * into the process's output.
     */
    private record Native(
            MethodHandle createFile,
            MethodHandle getInfo,
            MethodHandle getByHandle,
            MethodHandle closeHandle,
            @Nullable MethodHandle byName) {
        static @Nullable Native load() {
            if (!Os.isWindows() || !WindowsFileIds.class.getModule().isNativeAccessEnabled()) return null;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                MethodHandle create = linker.downcallHandle(
                        k32.find("CreateFileW").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS));
                MethodHandle info = linker.downcallHandle(
                        k32.find("GetFileInformationByHandleEx").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT));
                MethodHandle byHandle = linker.downcallHandle(
                        k32.find("GetFileInformationByHandle").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MethodHandle close = linker.downcallHandle(
                        k32.find("CloseHandle").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                // Windows 11 24H2 and later; older builds keep the handle path for every file.
                MethodHandle byName = k32.find("GetFileInformationByName")
                        .map(sym -> linker.downcallHandle(
                                sym,
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT)))
                        .orElse(null);
                return new Native(create, info, byHandle, close, byName);
            } catch (Throwable unavailable) {
                return null;
            }
        }
    }
}
