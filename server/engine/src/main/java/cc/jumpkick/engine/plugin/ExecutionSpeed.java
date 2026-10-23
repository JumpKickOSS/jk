// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;

/**
 * On Windows, the engine and every process it forks run at full execution speed: each is opted out
 * of power throttling ({@code SetProcessInformation(ProcessPowerThrottling)} with {@code
 * EXECUTION_SPEED} controlled and off). Windows otherwise treats a windowless daemon as background
 * work, and on a hybrid CPU it runs that work on the efficiency cores while performance cores sit
 * idle. The setting is per process and a child does not inherit it, so it is applied to each child
 * as it starts. Elsewhere, and where a call fails, this does nothing.
 *
 * @see <a href="https://learn.microsoft.com/windows/win32/api/processthreadsapi/nf-processthreadsapi-setprocessinformation">SetProcessInformation</a>
 */
public final class ExecutionSpeed {

    private static final int PROCESS_POWER_THROTTLING = 4;
    private static final int THROTTLING_EXECUTION_SPEED = 0x1;
    private static final int PROCESS_SET_INFORMATION = 0x0200;

    /** {@code PROCESS_POWER_THROTTLING_STATE}: version, control mask, state mask. */
    private static final long STATE_BYTES = 12;

    private static final @Nullable Native NATIVE = Os.isWindows() ? Native.load() : null;

    private ExecutionSpeed() {}

    /** Opt this process out of power throttling. */
    public static void self() {
        Native n = NATIVE;
        if (n == null) return;
        try {
            if (!n.set(MemorySegment.ofAddress(-1L))) Log.debug("execution speed: could not set for the engine");
        } catch (Throwable t) {
            Log.debug("execution speed: SetProcessInformation failed", t);
        }
    }

    /** Opt {@code child} out of power throttling; one that has already exited is left alone. */
    public static void child(Process child) {
        Native n = NATIVE;
        if (n == null) return;
        try {
            MemorySegment handle = (MemorySegment) n.open.invokeExact(PROCESS_SET_INFORMATION, 0, (int) child.pid());
            if (handle.address() == 0) return;
            try {
                if (!n.set(handle)) Log.debug("execution speed: could not set for pid " + child.pid());
            } finally {
                if ((int) n.close.invokeExact(handle) == 0) Log.debug("execution speed: CloseHandle failed");
            }
        } catch (Throwable t) {
            Log.debug("execution speed: SetProcessInformation failed for pid " + child.pid(), t);
        }
    }

    private record Native(MethodHandle open, MethodHandle setInfo, MethodHandle close) {

        static @Nullable Native load() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                return new Native(
                        linker.downcallHandle(
                                kernel32.find("OpenProcess").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                kernel32.find("SetProcessInformation").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT)),
                        linker.downcallHandle(
                                kernel32.find("CloseHandle").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
            } catch (Throwable unavailable) {
                Log.debug("execution speed: kernel32 calls unavailable", unavailable);
                return null;
            }
        }

        boolean set(MemorySegment process) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(STATE_BYTES, 4);
                state.set(ValueLayout.JAVA_INT, 0, 1);
                state.set(ValueLayout.JAVA_INT, 4, THROTTLING_EXECUTION_SPEED);
                state.set(ValueLayout.JAVA_INT, 8, 0);
                return (int) setInfo.invokeExact(process, PROCESS_POWER_THROTTLING, state, (int) STATE_BYTES) != 0;
            }
        }
    }
}
