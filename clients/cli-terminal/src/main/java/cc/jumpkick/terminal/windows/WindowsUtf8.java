// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.windows;

import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.Terminals;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Windows console UTF-8 bootstrap: CP 65001, VTP on {@code STD_OUTPUT_HANDLE}, UTF-8 streams.
 * Flush policy follows {@link Terminals#stdoutIsTty()}, not {@code System.console()}.
 */
public final class WindowsUtf8 {
    public static final int CP_UTF8 = 65_001;
    public static final int STD_OUTPUT_HANDLE = -11;
    public static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x4;

    private static volatile boolean enabled;
    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static int previousOutputCp = -1;
    private static int previousInputCp = -1;

    private WindowsUtf8() {}

    public static void enable() {
        if (enabled || !Os.isWindows()) {
            return;
        }
        synchronized (WindowsUtf8.class) {
            if (enabled) {
                return;
            }
            try {
                saveAndSetUtf8CodePages();
                enableVirtualTerminalProcessing();
            } catch (Throwable ignored) {
                // still retarget Java streams
            }
            originalOut = System.out;
            originalErr = System.err;
            System.setOut(utf8Stream(FileDescriptor.out));
            System.setErr(utf8Stream(FileDescriptor.err));
            enabled = true;
        }
    }

    /** Restore code pages if they are still 65001. No second shutdown hook — {@link Terminals#shutdown} owns this. */
    public static void restoreCodePages() {
        if (!Os.isWindows()) {
            return;
        }
        try {
            MethodHandle getOutput = downcall("GetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle getInput = downcall("GetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle setOutput =
                    downcall("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            MethodHandle setInput =
                    downcall("SetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            if (previousOutputCp > 0 && (int) getOutput.invokeExact() == CP_UTF8) {
                int ignoredOut = (int) setOutput.invokeExact(previousOutputCp);
            }
            if (previousInputCp > 0 && (int) getInput.invokeExact() == CP_UTF8) {
                int ignoredIn = (int) setInput.invokeExact(previousInputCp);
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    static PrintStream utf8Stream(FileDescriptor fd) {
        boolean autoflush = Terminals.stdoutIsTty();
        return new PrintStream(
                new BufferedOutputStream(new FileOutputStream(fd), autoflush ? 128 : 8192),
                autoflush,
                StandardCharsets.UTF_8);
    }

    private static void saveAndSetUtf8CodePages() throws Throwable {
        MethodHandle getOutput = downcall("GetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle getInput = downcall("GetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle setOutput =
                downcall("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle setInput =
                downcall("SetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int outCp = (int) getOutput.invokeExact();
        int inCp = (int) getInput.invokeExact();
        int ignoredOutCp = (int) setOutput.invokeExact(CP_UTF8);
        int ignoredInCp = (int) setInput.invokeExact(CP_UTF8);
        previousOutputCp = outCp > 0 && outCp != CP_UTF8 ? outCp : -1;
        previousInputCp = inCp > 0 && inCp != CP_UTF8 ? inCp : -1;
    }

    private static void enableVirtualTerminalProcessing() throws Throwable {
        MethodHandle getStdHandle =
                downcall("GetStdHandle", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle getMode = downcall(
                "GetConsoleMode",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle setMode = downcall(
                "SetConsoleMode",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(STD_OUTPUT_HANDLE);
        if (handle.address() == 0L || handle.address() == -1L) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment modeBuf = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) getMode.invokeExact(handle, modeBuf);
            if (ok == 0) {
                return;
            }
            int mode = modeBuf.get(ValueLayout.JAVA_INT, 0);
            if ((mode & ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0) {
                return;
            }
            int ignoredSetMode = (int) setMode.invokeExact(handle, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
        }
    }

    private static final class Kernel32 {
        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup("kernel32", Arena.global());
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        MemorySegment sym = Kernel32.LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return Linker.nativeLinker().downcallHandle(sym, desc);
    }
}
