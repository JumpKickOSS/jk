// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.posix.PosixTty;
import cc.jumpkick.terminal.windows.WindowsConsole;
import cc.jumpkick.terminal.windows.WindowsUtf8;
import java.io.Console;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/** Process façade. One controlling TTY. Never owns FD 0/1/2 of the process. */
public final class Terminals {
    private static final Object LOCK = new Object();
    private static volatile @Nullable NativeTerminal singleton;
    private static volatile Runnable beforeForeground = () -> {};

    private static final int STD_ERROR_HANDLE = -12;

    /** Windows {@code STD_INPUT_HANDLE}: the process's stdin, not the console {@code CONIN$} opens. */
    static final int STD_INPUT_HANDLE = -10;

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
     * on {@code CONIN$}, and only when stdin is that console: a Windows process nearly always has a
     * console (an ssh session, Git Bash, a redirected cmd) whether or not a person types into it.
     * Does not emit bytes.
     */
    public static boolean controllingIsTty() {
        return controllingIsTty(Os.isWindows(), () -> controlling().isLive(), Terminals::stdinIsTty);
    }

    /** {@link #controllingIsTty()} with the live-terminal and stdin checks as seams. */
    static boolean controllingIsTty(boolean windows, BooleanSupplier live, BooleanSupplier stdin) {
        return live.getAsBoolean() && (!windows || stdin.getAsBoolean());
    }

    /**
     * Stdin axis: whether the process's own stdin is a terminal. POSIX {@code isatty(0)}; Windows
     * {@code GetConsoleMode} on {@code STD_INPUT_HANDLE}. Unlike {@link #controllingIsTty()} this is
     * false under {@code < file} or a pipe even when a terminal or console is attached, which on
     * Windows (Git Bash, PowerShell) it nearly always is.
     */
    public static boolean stdinIsTty() {
        return stdinIsTty(Os.isWindows(), new Probe() {
            @Override
            public boolean windowsIsConsole(int stdHandle) throws Throwable {
                return Terminals.windowsIsConsole(stdHandle);
            }

            @Override
            public boolean posixIsatty(int fd) throws Throwable {
                return Terminals.posixIsatty(fd);
            }
        });
    }

    /** The native checks {@link #stdinIsTty(boolean, Probe)} makes; a seam for the platform it is not on. */
    interface Probe {
        boolean windowsIsConsole(int stdHandle) throws Throwable;

        boolean posixIsatty(int fd) throws Throwable;
    }

    /**
     * {@link #stdinIsTty()} on Windows or POSIX through {@code probe}. When the native check cannot
     * run, a {@link java.io.Console} that is a terminal: on JDK 22+ {@code System.console()} is
     * non-null even with stdin redirected, so its presence alone says nothing.
     */
    static boolean stdinIsTty(boolean windows, Probe probe) {
        try {
            return windows ? probe.windowsIsConsole(STD_INPUT_HANDLE) : probe.posixIsatty(0);
        } catch (Throwable ignored) {
            Console console = System.console();
            return console != null && console.isTerminal();
        }
    }

    /**
     * Output axis for animation. POSIX {@code isatty(1)}; Windows {@code GetConsoleMode} on
     * {@code STD_OUTPUT_HANDLE}. Fallback when FFM is unavailable: {@code System.console() != null}.
     */
    public static boolean stdoutIsTty() {
        try {
            if (Os.isWindows()) {
                return windowsIsConsole(WindowsUtf8.STD_OUTPUT_HANDLE);
            }
            return posixIsatty(1);
        } catch (Throwable ignored) {
            return System.console() != null;
        }
    }

    /** Error axis: POSIX {@code isatty(2)}; Windows {@code GetConsoleMode} on {@code STD_ERROR_HANDLE}. */
    public static boolean stderrIsTty() {
        try {
            if (Os.isWindows()) {
                return windowsIsConsole(STD_ERROR_HANDLE);
            }
            return posixIsatty(2);
        } catch (Throwable ignored) {
            return System.console() != null;
        }
    }

    /**
     * Run {@code hook} whenever something takes the terminal in the foreground: a raw or prompt
     * mode, the {@code /dev/tty} writer, or a child handoff. Their bytes bypass {@link System#out}.
     */
    public static void beforeForeground(Runnable hook) {
        beforeForeground = hook;
    }

    static void foreground() {
        beforeForeground.run();
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
        // The rewired streams autoflush per line; this catches an unterminated print() tail.
        System.out.flush();
        System.err.flush();
        WindowsUtf8.restoreCodePages();
    }

    /**
     * Before this process stops itself (Ctrl-Z): the terminal back to the shell's settings and the
     * cursor shown. {@link #resumeAfterStop} puts jk's mode back.
     */
    public static void suspendForStop() {
        NativeTerminal s = singleton;
        if (s != null) s.suspendForStop();
        System.err.print("\u001B[?25h");
        System.out.flush();
        System.err.flush();
    }

    /** After this process continues ({@code fg}): jk's terminal mode again. */
    public static void resumeAfterStop() {
        NativeTerminal s = singleton;
        if (s != null) s.resumeAfterStop();
    }

    public static void restoreForChild() {
        foreground();
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
    private static boolean windowsIsConsole(int stdHandle) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle getStdHandle = linker.downcallHandle(
                k32.findOrThrow("GetStdHandle"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle getMode = linker.downcallHandle(
                k32.findOrThrow("GetConsoleMode"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(stdHandle);
        if (handle.address() == 0L || handle.address() == -1L) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
            return (int) getMode.invokeExact(handle, mode) != 0;
        }
    }
}
