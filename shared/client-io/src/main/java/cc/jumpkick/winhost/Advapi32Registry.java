// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * {@code RegGetValueW} from {@code advapi32} through the foreign-function API, reading the 64-bit
 * view. The handle is bound on first use, never while a native image is built; its shape is
 * registered in this module's {@code reachability-metadata.json}.
 */
final class Advapi32Registry implements Registry {

    private static final int RRF_RT_REG_SZ = 0x2;
    private static final int RRF_RT_REG_EXPAND_SZ = 0x4;
    private static final int RRF_RT_REG_DWORD = 0x10;
    private static final int RRF_SUBKEY_WOW6464KEY = 0x10000;
    private static final int ERROR_SUCCESS = 0;
    private static final int MAX_STRING_BYTES = 64 * 1024;

    /** {@code HKEY_LOCAL_MACHINE} and {@code HKEY_CURRENT_USER}, sign-extended as the headers define them. */
    private static final long HKLM = 0xFFFF_FFFF_8000_0002L;

    private static final long HKCU = 0xFFFF_FFFF_8000_0001L;

    private final MethodHandle regGetValue;

    private Advapi32Registry(MethodHandle regGetValue) {
        this.regGetValue = regGetValue;
    }

    private static final class Holder {
        static final Registry BOUND = bind();
    }

    /** This process's registry, or {@link Registry#NONE} where advapi32 cannot be bound. */
    static Registry live() {
        return Holder.BOUND;
    }

    @SuppressWarnings("restricted")
    private static Registry bind() {
        if (!Os.isWindows() || !Advapi32Registry.class.getModule().isNativeAccessEnabled()) return Registry.NONE;
        try {
            SymbolLookup advapi = SymbolLookup.libraryLookup("advapi32", Arena.global());
            MethodHandle get = Linker.nativeLinker()
                    .downcallHandle(
                            advapi.findOrThrow("RegGetValueW"),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS));
            return new Advapi32Registry(get);
        } catch (Throwable unbound) {
            return Registry.NONE;
        }
    }

    @Override
    public @Nullable Integer dword(Hive hive, String key, String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment size = arena.allocate(ValueLayout.JAVA_INT);
            size.set(ValueLayout.JAVA_INT, 0, 4);
            int status = call(arena, hive, key, value, RRF_RT_REG_DWORD, data, size);
            return status == ERROR_SUCCESS ? data.get(ValueLayout.JAVA_INT, 0) : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    @Override
    public @Nullable String string(Hive hive, String key, String value) {
        int flags = RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(ValueLayout.JAVA_INT);
            if (call(arena, hive, key, value, flags, MemorySegment.NULL, size) != ERROR_SUCCESS) return null;
            int bytes = size.get(ValueLayout.JAVA_INT, 0);
            if (bytes <= 0 || bytes > MAX_STRING_BYTES) return null;
            MemorySegment data = arena.allocate(bytes + 2L);
            size.set(ValueLayout.JAVA_INT, 0, bytes);
            if (call(arena, hive, key, value, flags, data, size) != ERROR_SUCCESS) return null;
            int read = size.get(ValueLayout.JAVA_INT, 0);
            String text = new String(data.asSlice(0, read).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
            int nul = text.indexOf('\0');
            return nul >= 0 ? text.substring(0, nul) : text;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private int call(Arena arena, Hive hive, String key, String value, int type, MemorySegment data, MemorySegment size)
            throws Throwable {
        MemorySegment root = MemorySegment.ofAddress(hive == Hive.LOCAL_MACHINE ? HKLM : HKCU);
        return (int) regGetValue.invokeExact(
                root,
                arena.allocateFrom(key, StandardCharsets.UTF_16LE),
                arena.allocateFrom(value, StandardCharsets.UTF_16LE),
                type | RRF_SUBKEY_WOW6464KEY,
                MemorySegment.NULL,
                data,
                size);
    }
}
