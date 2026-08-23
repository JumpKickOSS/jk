// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.terminal.posix.PosixTty;
import cc.jumpkick.terminal.windows.WindowsConsole;
import cc.jumpkick.terminal.windows.WindowsUtf8;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Process façade. One controlling TTY. Never owns FD 0/1/2 of the process. */
public final class Terminals {
    private static final Object LOCK = new Object();
    private static volatile NativeTerminal singleton;

    private Terminals() {}

    /** Windows: CP 65001, VTP, UTF-8 System.out/err. POSIX: no-op. Idempotent. */
    public static void bootstrap() {
        WindowsUtf8.enable();
    }

    public static TerminalSession controlling() {
        NativeTerminal existing = singleton;
        if (existing != null) {
            return existing;
        }
        synchronized (LOCK) {
            if (singleton != null) {
                return singleton;
            }
            if (Os.isWindows()) {
                WindowsConsole win = WindowsConsole.openControlling();
                if (win == null) {
                    return DeadTerminal.INSTANCE;
                }
                singleton = new NativeTerminal(null, win);
                return singleton;
            }
            PosixTty posix = PosixTty.openControlling();
            if (posix == null) {
                return DeadTerminal.INSTANCE;
            }
            singleton = new NativeTerminal(posix, null);
            return singleton;
        }
    }

    /**
     * Input axis. POSIX {@code isatty} on a private {@code /dev/tty} fd; Windows {@code GetConsoleMode}
     * on {@code CONIN$}. Does not emit bytes.
     */
    public static boolean controllingIsTty() {
        TerminalSession s = controlling();
        return s.isLive();
    }

    /**
     * Output axis for animation. POSIX {@code isatty(1)}; Windows {@code GetConsoleMode} on
     * {@code STD_OUTPUT_HANDLE}. Fallback when FFM is unavailable: {@code System.console() != null}.
     */
    public static boolean stdoutIsTty() {
        try {
            if (Os.isWindows()) {
                return windowsStdoutIsTty();
            }
            return posixIsatty(1);
        } catch (Throwable ignored) {
            return System.console() != null;
        }
    }

    public static MemoryTerminal memory(InputStream in, OutputStream out) {
        return new MemoryTerminal(in, out);
    }

    public static void shutdown() {
        NativeTerminal s;
        synchronized (LOCK) {
            s = singleton;
            singleton = null;
        }
        if (s != null) {
            s.shutdown();
        }
        WindowsUtf8.restoreCodePages();
    }

    public static void restoreForChild() {
        NativeTerminal s = singleton;
        if (s != null) {
            s.restoreForChild();
        }
    }

    @SuppressWarnings("restricted")
    private static boolean posixIsatty(int fd) throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle isatty = linker.downcallHandle(
                linker.defaultLookup().findOrThrow("isatty"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        return (int) isatty.invokeExact(fd) == 1;
    }

    @SuppressWarnings("restricted")
    private static boolean windowsStdoutIsTty() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle getStdHandle = linker.downcallHandle(
                k32.findOrThrow("GetStdHandle"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle getMode = linker.downcallHandle(
                k32.findOrThrow("GetConsoleMode"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(WindowsUtf8.STD_OUTPUT_HANDLE);
        if (handle.address() == 0L || handle.address() == -1L) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
            return (int) getMode.invokeExact(handle, mode) != 0;
        }
    }
}
