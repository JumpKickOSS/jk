// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.terminal.Signals;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Process-wide terminal-size cache. The probe uses Panama FFM system calls —
 * {@code ioctl(TIOCGWINSZ)} on POSIX and {@code GetConsoleScreenBufferInfo} on Windows — then
 * {@code $LINES}/{@code $COLUMNS}, then conservative defaults. No subprocess, no ANSI queries,
 * and no JLine terminal (JLine capability probes race the shell after a transient build-close).
 *
 * <p>The probe runs once and the result is reused; {@link #refresh()} re-probes at natural
 * boundaries (the start of a live plan), which also picks up a resize between builds. SIGWINCH
 * only clears the cache — the next {@link #size()} / {@link #columns()} pays one native probe.
 * Live plan paint and {@link RenderContext#current()} read that cache every frame; they must never
 * call {@link #refresh()}.
 */
public final class TerminalSize {

    static final int DEFAULT_WIDTH = 80;
    static final int DEFAULT_HEIGHT = 24;

    /** Injectable for tests; production probes the OS. */
    static Supplier<int[]> probe = TerminalSize::probeOs;

    private static volatile int[] cached;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * SIGWINCH invalidation: with the cache probed only at plan start, everything
     * rendered after a mid-build resize — failure-snippet budgets, settle wedges — used the stale
     * width until the next plan. The handler only drops the cache (never probes); the next
     * consumer pays one native ioctl per physical resize, not per frame. Installed lazily at
     * the first runtime probe so native-image build-time class init never registers a handler.
     * Registered via {@link Signals#register} (reflective {@code sun.misc.Signal} wrapper) so the
     * compiler never sees an internal proprietary API. Best-effort: platforms without WINCH
     * (Windows, exotic runtimes) keep the plan-start-only behavior.
     */
    private static volatile boolean winchAttempted;

    private static void ensureWinchHandler() {
        if (winchAttempted) return;
        winchAttempted = true;
        if (IS_WINDOWS) {
            return;
        }
        try {
            // Same reflective path as GlobalCancel — avoids sun.misc compile warnings.
            Signals.register("WINCH", TerminalSize::onResize);
        } catch (Throwable t) {
            // unsupported runtime — the plan-start refresh still applies
        }
    }

    /**
     * Resize invalidation. The generation bump comes FIRST: a probe that was already in flight
     * when the resize landed re-checks the generation before caching, so its (possibly pre-resize)
     * result cannot overwrite the invalidation. Bump-then-clear, because
     * clear-then-bump reopens the window: the probe could store between the two.
     */
    static void onResize() {
        resizeGeneration.incrementAndGet();
        cached = null;
    }

    private static final AtomicInteger resizeGeneration = new AtomicInteger();

    private TerminalSize() {}

    /** Cached {@code {rows, cols}}; probes on first use. */
    public static int[] size() {
        ensureWinchHandler();
        int[] s = cached;
        if (s == null) {
            int gen = resizeGeneration.get();
            s = probe.get();
            // A WINCH mid-probe means this result may be pre-resize: return it (best effort for
            // this frame) but leave the cache empty so the next consumer re-probes.
            if (resizeGeneration.get() == gen) cached = s;
        }
        return s;
    }

    /**
     * Terminal width in columns (native size → {@code $COLUMNS} → {@value #DEFAULT_WIDTH}).
     */
    public static int columns() {
        return size()[1];
    }

    /** Re-probe and cache — call at plan start, never from a render path. */
    public static int[] refresh() {
        ensureWinchHandler();
        int gen = resizeGeneration.get();
        int[] s = probe.get();
        if (resizeGeneration.get() == gen) cached = s;
        return s;
    }

    /** Test hook: forget the cached size. */
    static void reset() {
        cached = null;
    }

    /** Production probe: FFM native size, else env, else defaults. Never throws. */
    private static int[] probeOs() {
        try {
            int[] nativeSize = IS_WINDOWS ? probeWindows() : probePosix();
            if (nativeSize != null) {
                return nativeSize;
            }
        } catch (Throwable ignored) {
            // linkage / restricted / no tty — fall through
        }
        return envSize();
    }

    /** {@code $LINES}/{@code $COLUMNS} or {@link #DEFAULT_HEIGHT}/{@link #DEFAULT_WIDTH}. */
    static int[] envSize() {
        return new int[] {envInt("LINES", DEFAULT_HEIGHT), envInt("COLUMNS", DEFAULT_WIDTH)};
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            }
        } catch (NumberFormatException ignored) {
            // unparsable env — fall through
        }
        return fallback;
    }

    // --- POSIX: ioctl(TIOCGWINSZ) ---------------------------------------------------------------

    private static final int O_RDONLY = 0;

    /** Lazy POSIX downcalls — resolved on first probe, never at class init. */
    private static volatile MethodHandle posixOpen;

    private static volatile MethodHandle posixClose;
    private static volatile MethodHandle posixIsatty;
    private static volatile MethodHandle posixIoctl;
    private static volatile long posixTiocgwinsz;
    private static volatile boolean posixInitAttempted;

    @SuppressWarnings("restricted")
    private static void ensurePosix() {
        if (posixInitAttempted) return;
        synchronized (TerminalSize.class) {
            if (posixInitAttempted) return;
            // The volatile flag is written LAST (finally): the unsynchronized fast path above
            // reads it without the monitor, so publishing it before the handles let a second
            // thread see attempted=true with null handles and cache the 80x24 env fallback as
            // the process-wide size. finally keeps a linker failure from re-throwing
            // on every later probe.
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
                // ioctl is variadic; third arg is the winsize pointer.
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

    /** {@code TIOCGWINSZ} request codes (same table JLine terminal-ffm uses). */
    private static long tiocgwinszConstant() {
        String os = System.getProperty("os.name", "");
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
        // Other POSIX-ish: the Linux/x86 value is the common default.
        return 0x5413L;
    }

    /**
     * Prefer the controlling terminal ({@code /dev/tty}), else any of stdout / stderr / stdin that
     * is a tty.
     */
    @SuppressWarnings("restricted")
    private static int[] probePosix() throws Throwable {
        ensurePosix();
        if (posixIoctl == null) return null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom("/dev/tty");
            int fd = (int) posixOpen.invokeExact(path, O_RDONLY);
            if (fd >= 0) {
                try {
                    int[] size = winsizeFromFd(fd, arena);
                    if (size != null) return size;
                } finally {
                    int ignore = (int) posixClose.invokeExact(fd);
                }
            }
            for (int candidate : new int[] {1, 2, 0}) {
                if ((int) posixIsatty.invokeExact(candidate) == 1) {
                    int[] size = winsizeFromFd(candidate, arena);
                    if (size != null) return size;
                }
            }
        }
        return null;
    }

    /** {@code struct winsize}: four unsigned shorts; we only read row/col. */
    @SuppressWarnings("restricted")
    private static int[] winsizeFromFd(int fd, Arena arena) throws Throwable {
        MemorySegment ws = arena.allocate(8);
        int rc = (int) posixIoctl.invoke(fd, posixTiocgwinsz, ws);
        if (rc != 0) return null;
        int rows = Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 0));
        int cols = Short.toUnsignedInt(ws.get(ValueLayout.JAVA_SHORT, 2));
        if (rows > 0 && cols > 0) {
            return new int[] {rows, cols};
        }
        return null;
    }

    // --- Windows: GetConsoleScreenBufferInfo ----------------------------------------------------

    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int STD_ERROR_HANDLE = -12;

    /**
     * {@code CONSOLE_SCREEN_BUFFER_INFO} (no padding between fields; all 2-byte aligned):
     *
     * <pre>
     * COORD dwSize;              // 0
     * COORD dwCursorPosition;    // 4
     * WORD  wAttributes;         // 8
     * SMALL_RECT srWindow;       // 10  (Left, Top, Right, Bottom)
     * COORD dwMaximumWindowSize; // 18
     * </pre>
     */
    private static final GroupLayout CONSOLE_SCREEN_BUFFER_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT, // dwSize
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT, // dwCursorPosition
            ValueLayout.JAVA_SHORT, // wAttributes
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT, // srWindow
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT); // dwMaximumWindowSize

    private static final long SR_WINDOW_OFFSET = 10;

    private static volatile MethodHandle winGetStdHandle;
    private static volatile MethodHandle winGetConsoleScreenBufferInfo;
    private static volatile boolean winInitAttempted;

    @SuppressWarnings("restricted")
    private static void ensureWindows() {
        if (winInitAttempted) return;
        synchronized (TerminalSize.class) {
            if (winInitAttempted) return;
            // Same shape as ensurePosix: the volatile flag is written LAST (finally). The
            // unsynchronized fast path reads it without the monitor, so publishing it before the
            // handles lets a second thread see attempted=true with null handles and cache the
            // 80x24 env fallback process-wide — and Windows has no WINCH to recover. finally
            // keeps a linker failure from re-throwing on every later probe.
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
    private static int[] probeWindows() throws Throwable {
        ensureWindows();
        if (winGetConsoleScreenBufferInfo == null) return null;

        try (Arena arena = Arena.ofConfined()) {
            for (int std : new int[] {STD_OUTPUT_HANDLE, STD_ERROR_HANDLE}) {
                MemorySegment handle = (MemorySegment) winGetStdHandle.invokeExact(std);
                if (handle == null || handle.address() == 0L || handle.address() == -1L) {
                    continue;
                }
                MemorySegment info = arena.allocate(CONSOLE_SCREEN_BUFFER_INFO);
                int ok = (int) winGetConsoleScreenBufferInfo.invokeExact(handle, info);
                if (ok == 0) continue;
                int left = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET));
                int top = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET + 2));
                int right = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET + 4));
                int bottom = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, SR_WINDOW_OFFSET + 6));
                int cols = right - left + 1;
                int rows = bottom - top + 1;
                if (rows > 0 && cols > 0) {
                    return new int[] {rows, cols};
                }
            }
        }
        return null;
    }
}
