// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * {@code ShellExecuteExW} with the {@code runas} verb on the signed {@code powershell.exe}, then
 * {@code WaitForSingleObject} and {@code GetExitCodeProcess} on the process it started. The prompt's
 * owner is this process's console window when it has one, so the prompt comes up in front of the
 * terminal that asked; a process without a window (the engine) gets a flashing taskbar button.
 * Handles are bound on first use; their shapes are in this module's {@code reachability-metadata.json}.
 */
final class Shell32Elevator implements Elevator {

    static final Shell32Elevator INSTANCE = new Shell32Elevator();

    private static final int SEE_MASK_NOCLOSEPROCESS = 0x40;
    private static final int SEE_MASK_NOASYNC = 0x100;
    private static final int SEE_MASK_FLAG_NO_UI = 0x400;
    private static final int SW_HIDE = 0;
    private static final int ERROR_CANCELLED = 1223;
    private static final int WAIT_OBJECT_0 = 0;

    /** {@code SHELLEXECUTEINFOW} on 64-bit Windows: 112 bytes. */
    static final StructLayout INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cbSize"),
            ValueLayout.JAVA_INT.withName("fMask"),
            ValueLayout.ADDRESS.withName("hwnd"),
            ValueLayout.ADDRESS.withName("lpVerb"),
            ValueLayout.ADDRESS.withName("lpFile"),
            ValueLayout.ADDRESS.withName("lpParameters"),
            ValueLayout.ADDRESS.withName("lpDirectory"),
            ValueLayout.JAVA_INT.withName("nShow"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("hInstApp"),
            ValueLayout.ADDRESS.withName("lpIDList"),
            ValueLayout.ADDRESS.withName("lpClass"),
            ValueLayout.ADDRESS.withName("hkeyClass"),
            ValueLayout.JAVA_INT.withName("dwHotKey"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("hIcon"),
            ValueLayout.ADDRESS.withName("hProcess"));

    private static long offset(String field) {
        return INFO.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private record Calls(
            MethodHandle shellExecuteEx,
            MethodHandle waitForSingleObject,
            MethodHandle getExitCodeProcess,
            MethodHandle closeHandle,
            MethodHandle getConsoleWindow,
            StructLayout state,
            VarHandle lastError) {}

    private static final class Holder {
        static final @Nullable Calls CALLS = bind();
    }

    private Shell32Elevator() {}

    @SuppressWarnings("restricted")
    private static @Nullable Calls bind() {
        if (!Os.isWindows() || !Shell32Elevator.class.getModule().isNativeAccessEnabled()) return null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", Arena.global());
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            Linker.Option capture = Linker.Option.captureCallState("GetLastError");
            StructLayout state = Linker.Option.captureStateLayout();
            return new Calls(
                    linker.downcallHandle(
                            shell32.findOrThrow("ShellExecuteExW"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            capture),
                    linker.downcallHandle(
                            kernel32.findOrThrow("WaitForSingleObject"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("GetExitCodeProcess"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("CloseHandle"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("GetConsoleWindow"), FunctionDescriptor.of(ValueLayout.ADDRESS)),
                    state,
                    state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError")));
        } catch (Throwable unbound) {
            return null;
        }
    }

    @Override
    public Outcome run(String script, Duration timeout) {
        Calls c = Holder.CALLS;
        if (c == null) return new Outcome.Failed(0, "elevation needs Windows and native access");
        String root = ProcessEnv.get("SystemRoot");
        String powershell =
                (root == null ? "C:\\Windows" : root) + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(INFO);
            MemorySegment state = arena.allocate(c.state());
            info.set(ValueLayout.JAVA_INT, offset("cbSize"), (int) INFO.byteSize());
            info.set(
                    ValueLayout.JAVA_INT,
                    offset("fMask"),
                    SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI);
            info.set(ValueLayout.ADDRESS, offset("hwnd"), (MemorySegment)
                    c.getConsoleWindow().invokeExact());
            info.set(ValueLayout.ADDRESS, offset("lpVerb"), arena.allocateFrom("runas", StandardCharsets.UTF_16LE));
            info.set(ValueLayout.ADDRESS, offset("lpFile"), arena.allocateFrom(powershell, StandardCharsets.UTF_16LE));
            info.set(
                    ValueLayout.ADDRESS,
                    offset("lpParameters"),
                    arena.allocateFrom(ElevatedScript.arguments(script), StandardCharsets.UTF_16LE));
            info.set(ValueLayout.JAVA_INT, offset("nShow"), SW_HIDE);
            int ok = (int) c.shellExecuteEx().invokeExact(state, info);
            if (ok == 0) {
                int error = (int) c.lastError().get(state, 0L);
                if (error == ERROR_CANCELLED) return new Outcome.Declined();
                return new Outcome.Failed(error, "the Windows prompt could not be shown (error " + error + ")");
            }
            MemorySegment process = info.get(ValueLayout.ADDRESS, offset("hProcess"));
            if (process.address() == 0L) return new Outcome.Failed(0, "Windows started no process");
            try {
                int millis = (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(0, timeout.toMillis()));
                int waited = (int) c.waitForSingleObject().invokeExact(process, millis);
                if (waited != WAIT_OBJECT_0) return new Outcome.TimedOut();
                MemorySegment exit = arena.allocate(ValueLayout.JAVA_INT);
                int got = (int) c.getExitCodeProcess().invokeExact(process, exit);
                return new Outcome.Ran(got == 0 ? -1 : exit.get(ValueLayout.JAVA_INT, 0));
            } finally {
                int ignored = (int) c.closeHandle().invokeExact(process);
            }
        } catch (Throwable t) {
            return new Outcome.Failed(0, "elevation could not be called: " + t);
        }
    }
}
