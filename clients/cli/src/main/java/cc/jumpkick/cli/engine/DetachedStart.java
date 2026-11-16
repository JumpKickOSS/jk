// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;

/**
 * Starts a process that outlives this one without handing it handles this process only holds by
 * inheritance.
 *
 * <p>On Windows, {@link ProcessBuilder#start} calls {@code CreateProcess} with handle inheritance
 * on, and the child inherits every inheritable handle of this process, not only the redirects it
 * names (the JDK keeps this process's own standard handles out, nothing else). A client started by
 * a PowerShell whose output is a pipe holds that pipe twice: as its stdout, and as one more
 * inheritable handle PowerShell passed down. The engine it started inherited the second, so
 * whoever read the pipe (a piped {@code irm | iex}, CI, an agent) waited for an end of file that
 * came only when the engine exited. Before the start this clears {@code HANDLE_FLAG_INHERIT} on
 * every inheritable handle of this process other than the standard three, so the engine gets its
 * log and its stdin pipe and nothing else. This client inherits nothing it means to pass on. POSIX
 * children close every descriptor above stderr on exec, so there it is a plain start.
 */
final class DetachedStart {

    private DetachedStart() {}

    private static final int HANDLE_FLAG_INHERIT = 0x1;

    /** Handle values are multiples of four; a short-lived client holds far fewer than this many. */
    private static final long HANDLE_LIMIT = 0x10000;

    /** {@code pb} started with no inherited-only handle of this process passed on. */
    static Process startDetached(ProcessBuilder pb) throws IOException {
        if (!Os.isWindows()) return pb.start();
        @Nullable Kernel32 k = Kernel32.get();
        synchronized (DetachedStart.class) {
            if (k != null) k.clearInheritance();
            return pb.start();
        }
    }

    /** The kernel32 calls, bound once; null where they cannot be. */
    private record Kernel32(MethodHandle getStdHandle, MethodHandle getInfo, MethodHandle setInfo) {

        private static volatile @Nullable Kernel32 bound;
        private static volatile boolean tried;

        static @Nullable Kernel32 get() {
            if (!tried) {
                synchronized (Kernel32.class) {
                    if (!tried) {
                        try {
                            Linker linker = Linker.nativeLinker();
                            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                            bound = new Kernel32(
                                    linker.downcallHandle(
                                            k32.findOrThrow("GetStdHandle"),
                                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                                    linker.downcallHandle(
                                            k32.findOrThrow("GetHandleInformation"),
                                            FunctionDescriptor.of(
                                                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                                    linker.downcallHandle(
                                            k32.findOrThrow("SetHandleInformation"),
                                            FunctionDescriptor.of(
                                                    ValueLayout.JAVA_INT,
                                                    ValueLayout.ADDRESS,
                                                    ValueLayout.JAVA_INT,
                                                    ValueLayout.JAVA_INT)));
                        } catch (Throwable unavailable) {
                            bound = null;
                        }
                        tried = true;
                    }
                }
            }
            return bound;
        }

        /** Clears inheritance on every inheritable handle but the standard ones; answers how many. */
        int clearInheritance() {
            int cleared = 0;
            try (Arena arena = Arena.ofConfined()) {
                long in = ((MemorySegment) getStdHandle.invokeExact(-10)).address();
                long out = ((MemorySegment) getStdHandle.invokeExact(-11)).address();
                long err = ((MemorySegment) getStdHandle.invokeExact(-12)).address();
                MemorySegment flags = arena.allocate(ValueLayout.JAVA_INT);
                for (long value = 4; value < HANDLE_LIMIT; value += 4) {
                    if (value == in || value == out || value == err) continue;
                    MemorySegment h = MemorySegment.ofAddress(value);
                    if ((int) getInfo.invokeExact(h, flags) == 0) continue;
                    if ((flags.get(ValueLayout.JAVA_INT, 0) & HANDLE_FLAG_INHERIT) == 0) continue;
                    if ((int) setInfo.invokeExact(h, HANDLE_FLAG_INHERIT, 0) != 0) cleared++;
                }
            } catch (Throwable unavailable) {
                // The start goes ahead with the flags as they are.
            }
            return cleared;
        }
    }
}
