// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Process-wide terminal-size cache. Native ioctl / {@code GetConsoleScreenBufferInfo}, then
 * {@code $LINES}/{@code $COLUMNS}, then 24×80. SIGWINCH only bumps generation and clears cache.
 */
public final class Size {
    public static final int DEFAULT_WIDTH = 80;
    public static final int DEFAULT_HEIGHT = 24;

    public record Window(int rows, int cols) {
        public static final Window DEFAULT = new Window(DEFAULT_HEIGHT, DEFAULT_WIDTH);
    }

    public static Supplier<Window> probe = Size::probeOs;

    private static volatile @Nullable Window cached;
    private static volatile boolean winchAttempted;
    private static final AtomicInteger resizeGeneration = new AtomicInteger();

    private Size() {}

    public static Window current() {
        ensureWinchHandler();
        Window s = cached;
        if (s == null) {
            int gen = resizeGeneration.get();
            s = probe.get();
            if (resizeGeneration.get() == gen) {
                cached = s;
            }
        }
        return s;
    }

    public static int columns() {
        return current().cols();
    }

    /** Re-probe — call at plan start, never from a render path. */
    public static Window refresh() {
        ensureWinchHandler();
        int gen = resizeGeneration.get();
        Window s = probe.get();
        if (resizeGeneration.get() == gen) {
            cached = s;
        }
        return s;
    }

    public static void reset() {
        cached = null;
    }

    public static void onResize() {
        resizeGeneration.incrementAndGet();
        cached = null;
    }

    private static void ensureWinchHandler() {
        if (winchAttempted) {
            return;
        }
        winchAttempted = true;
        if (Os.isWindows()) {
            return;
        }
        Signals.register("WINCH", Size::onResize);
    }

    private static Window probeOs() {
        try {
            Window nativeSize = Os.isWindows() ? probeWindows() : probePosix();
            if (nativeSize != null) {
                return nativeSize;
            }
        } catch (Throwable ignored) {
            // linkage / no tty
        }
        return envSize();
    }

    public static Window envSize() {
        return new Window(envInt("LINES", DEFAULT_HEIGHT), envInt("COLUMNS", DEFAULT_WIDTH));
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n > 0) {
                    return n;
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    private static final int O_RDONLY = 0;
    private static volatile @Nullable MethodHandle posixOpen;
    private static volatile @Nullable MethodHandle posixClose;
    private static volatile @Nullable MethodHandle posixIsatty;
    private static volatile @Nullable MethodHandle posixIoctl;
    private static volatile long posixTiocgwinsz;
    private static volatile boolean posixInitAttempted;

    @SuppressWarnings("restricted")
    private static void ensurePosix() {
        if (posixInitAttempted) {
            return;
        }
        synchronized (Size.class) {
            if (posixInitAttempted) {
                return;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                posixOpen = linker.downcallHandle(
                        lookup.findOrThrow("open"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                posixClose = linker.downcallHandle(
                        lookup.findOrThrow("close"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                posixIsatty = linker.downcallHandle(
                        lookup.findOrThrow("isatty"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                posixIoctl = linker.downcallHandle(
                        lookup.findOrThrow("ioctl"),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                        Linker.Option.firstVariadicArg(2));
                posixTiocgwinsz = tiocgwinszConstant();
            } finally {
                posixInitAttempted = true;
            }
        }
    }

    static long tiocgwinszConstant() {
        String os = Os.name();
        String arch = System.getProperty("os.arch", "");
        if (os.startsWith("Linux")) {
            boolean isMipsPpcOrSparc = arch.equals("mips")
                    || arch.equals("mips64")
                    || arch.equals("mipsel")
                    || arch.equals("mips64el")
                    || arch.startsWith("ppc")
                    || arch.startsWith("sparc");
            return isMipsPpcOrSparc ? 0x40087468L : 0x5413L;
        }
        if (os.startsWith("Mac") || os.startsWith("Darwin") || os.startsWith("FreeBSD")) {
            return 0x40087468L;
        }
        if (os.startsWith("Solaris") || os.startsWith("SunOS")) {
            return (('T' << 8) | 104);
        }
        return 0x5413L;
    }

    @SuppressWarnings("restricted")
    private static @Nullable Window probePosix() throws Throwable {
        ensurePosix();
        MethodHandle ioctl = posixIoctl;
        MethodHandle open = posixOpen;
        MethodHandle close = posixClose;
        MethodHandle isatty = posixIsatty;
        if (ioctl == null || open == null || close == null || isatty == null) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom("/dev/tty");
            int fd = (int) open.invokeExact(path, O_RDONLY);
            if (fd >= 0) {
                try {
                    Window size = winsizeFromFd(ioctl, fd, arena);
                    if (size != null) {
                        return size;
                    }
                } finally {
                    int ignore = (int) close.invokeExact(fd);
                }
            }
            for (int candidate : new int[] {1, 2, 0}) {
                if ((int) isatty.invokeExact(candidate) == 1) {
                    Window size = winsizeFromFd(ioctl, candidate, arena);
                    if (size != null) {
                        return size;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("restricted")
    private static @Nullable Window winsizeFromFd(MethodHandle ioctl, int fd, Arena arena) throws Throwable {
        MemorySegment ws = arena.allocate(8);
        int rc = (int) ioctl.invoke(fd, posixTiocgwinsz, ws);
        if (rc != 0) {
            return null;
        }
        int rows = Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 0));
        int cols = Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 2));
        if (rows > 0 && cols > 0) {
            return new Window(rows, cols);
        }
        return null;
    }

    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;
    private static final GroupLayout CONSOLE_SCREEN_BUFFER_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT);
    private static final long SR_WINDOW_OFFSET = 10;
    private static volatile @Nullable MethodHandle winGetStdHandle;
    private static volatile @Nullable MethodHandle winGetConsoleScreenBufferInfo;
    private static volatile boolean winInitAttempted;

    @SuppressWarnings("restricted")
    private static void ensureWindows() {
        if (winInitAttempted) {
            return;
        }
        synchronized (Size.class) {
            if (winInitAttempted) {
                return;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                winGetStdHandle = linker.downcallHandle(
                        k32.findOrThrow("GetStdHandle"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                winGetConsoleScreenBufferInfo = linker.downcallHandle(
                        k32.findOrThrow("GetConsoleScreenBufferInfo"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            } finally {
                winInitAttempted = true;
            }
        }
    }

    @SuppressWarnings("restricted")
    private static @Nullable Window probeWindows() throws Throwable {
        ensureWindows();
        MethodHandle getStdHandle = winGetStdHandle;
        MethodHandle getInfo = winGetConsoleScreenBufferInfo;
        if (getInfo == null || getStdHandle == null) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            for (int std : new int[] {STD_OUTPUT_HANDLE, STD_ERROR_HANDLE}) {
                MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(std);
                if (handle == null || handle.address() == 0L || handle.address() == -1L) {
                    continue;
                }
                MemorySegment info = arena.allocate(CONSOLE_SCREEN_BUFFER_INFO);
                int ok = (int) getInfo.invokeExact(handle, info);
                if (ok == 0) {
                    continue;
                }
                int left = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET));
                int top = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET + 2));
                int right = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET + 4));
                int bottom = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET + 6));
                int cols = right - left + 1;
                int rows = bottom - top + 1;
                if (rows > 0 && cols > 0) {
                    return new Window(rows, cols);
                }
            }
        }
        return null;
    }
}
