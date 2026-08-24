// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * POSIX {@code /dev/tty}: open, termios, poll, non-blocking read/write. Never owns FD 0/1/2.
 */
public final class PosixTty implements AutoCloseable {
    private static final int SLICE_MS = 50;
    private static final MemoryLayout CAPTURED = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURED.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    private final int fd;
    private final byte[] original;
    private final boolean darwin;
    private volatile boolean open = true;

    private PosixTty(int fd, byte[] original, boolean darwin) {
        this.fd = fd;
        this.original = original;
        this.darwin = darwin;
    }

    public int fd() {
        return fd;
    }

    public static PosixTty openControlling() {
        if (Os.isWindows() || !(Os.isLinux() || Os.isDarwin())) {
            return null;
        }
        ensure();
        if (openMh == null) {
            return null;
        }
        boolean darwin = Os.isDarwin();
        int flags =
                darwin ? TermiosDarwin.O_RDWR | TermiosDarwin.O_CLOEXEC : TermiosLinux.O_RDWR | TermiosLinux.O_CLOEXEC;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom("/dev/tty");
            int fd = (int) openMh.invokeExact(path, flags);
            if (fd < 0) {
                return null;
            }
            if (fd == 0) {
                int ignored = (int) closeMh.invokeExact(fd);
                return null;
            }
            int oNonblock = darwin ? TermiosDarwin.O_NONBLOCK : TermiosLinux.O_NONBLOCK;
            int fGet = darwin ? TermiosDarwin.F_GETFL : TermiosLinux.F_GETFL;
            int fSet = darwin ? TermiosDarwin.F_SETFL : TermiosLinux.F_SETFL;
            // O_NONBLOCK is best-effort: a missing native-image fcntl descriptor must not
            // kill the session. poll + non-blocking read still work without it; a blocking
            // read after POLLIN is correct.
            if (fcntlMh != null) {
                int cur = (int) fcntlMh.invokeExact(fd, fGet, 0);
                if (cur >= 0) {
                    int set = (int) fcntlMh.invokeExact(fd, fSet, cur | oNonblock);
                }
            }
            if (tcgetattrMh == null) {
                int ignored = (int) closeMh.invokeExact(fd);
                return null;
            }
            int size = darwin ? TermiosDarwin.SIZE : TermiosLinux.SIZE;
            MemorySegment term = arena.allocate(size);
            MemorySegment state = arena.allocate(CAPTURED);
            int rc = (int) tcgetattrMh.invokeExact(state, fd, term);
            if (rc != 0) {
                int ignored = (int) closeMh.invokeExact(fd);
                return null;
            }
            byte[] original = term.toArray(ValueLayout.JAVA_BYTE);
            return new PosixTty(fd, original, darwin);
        } catch (Throwable t) {
            return null;
        }
    }

    public void apply(InputMode mode) {
        if (!open) {
            return;
        }
        ensure();
        int size = darwin ? TermiosDarwin.SIZE : TermiosLinux.SIZE;
        int tcsanow = darwin ? TermiosDarwin.TCSANOW : TermiosLinux.TCSANOW;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment term = arena.allocate(size);
            MemorySegment.copy(MemorySegment.ofArray(original), 0, term, 0, original.length);
            if (darwin) {
                TermiosDarwin.apply(term, mode);
            } else {
                TermiosLinux.apply(term, mode);
            }
            MemorySegment state = arena.allocate(CAPTURED);
            int rc = (int) tcsetattrMh.invokeExact(state, fd, tcsanow, term);
            if (rc < 0 && errno(state) == eintr()) {
                rc = (int) tcsetattrMh.invokeExact(state, fd, tcsanow, term);
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    public void restoreOriginal() {
        applyCooked();
    }

    private void applyCooked() {
        if (!open) {
            return;
        }
        ensure();
        int tcsanow = darwin ? TermiosDarwin.TCSANOW : TermiosLinux.TCSANOW;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment term = arena.allocate(original.length);
            MemorySegment.copy(MemorySegment.ofArray(original), 0, term, 0, original.length);
            MemorySegment state = arena.allocate(CAPTURED);
            int rc = (int) tcsetattrMh.invokeExact(state, fd, tcsanow, term);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    /**
     * Clock-driven wait. Returns a byte, or -1 on timeout / dead.
     * {@code timeout.isZero()} is forever.
     */
    public int readByte(Duration timeout, BooleanSupplier live) {
        DeadTerminalTimeout.check(timeout);
        boolean forever = timeout.isZero();
        long deadline = forever ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        ensure();
        if (pollMh == null) {
            return -1;
        }
        while (live.getAsBoolean()) {
            if (!forever && System.nanoTime() >= deadline) {
                return -1;
            }
            long remaining = forever ? Long.MAX_VALUE : Math.max(0, deadline - System.nanoTime());
            int timeoutMs = forever ? -1 : (int) Math.min(Integer.MAX_VALUE, remaining / 1_000_000L);
            long t0 = System.nanoTime();
            int rc;
            int err;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CAPTURED);
                MemorySegment fds = arena.allocate(8);
                fds.set(ValueLayout.JAVA_INT, 0, fd);
                fds.set(ValueLayout.JAVA_SHORT, 4, (short) pollin());
                fds.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
                rc = poll(state, fds, timeoutMs);
                err = rc < 0 ? errno(state) : 0;
                if (rc > 0) {
                    MemorySegment buf = arena.allocate(1);
                    int n = readOne(state, buf);
                    if (n > 0) {
                        return Byte.toUnsignedInt(buf.get(ValueLayout.JAVA_BYTE, 0));
                    }
                    if (n < 0) {
                        err = errno(state);
                        if (err == eintr()) {
                            continue;
                        }
                        if (err == eagain()) {
                            long elapsed = System.nanoTime() - t0;
                            if (elapsed < 1_000_000L) {
                                sleepSlice(
                                        forever ? SLICE_MS * 1_000_000L : Math.min(remaining, SLICE_MS * 1_000_000L));
                            }
                            continue;
                        }
                        return -2; // dead
                    }
                    continue;
                }
            } catch (Throwable t) {
                return -2;
            }
            if (rc == 0) {
                return -1;
            }
            if (err == eintr()) {
                continue;
            }
            return -2;
        }
        return -1;
    }

    public void writeFully(byte[] buf, BooleanSupplier live) {
        if (buf.length == 0 || !open) {
            return;
        }
        ensure();
        int off = 0;
        while (off < buf.length && live.getAsBoolean() && open) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CAPTURED);
                MemorySegment src = arena.allocate(buf.length - off);
                MemorySegment.copy(MemorySegment.ofArray(buf), off, src, 0, buf.length - off);
                int n = writeSome(state, src, buf.length - off);
                if (n > 0) {
                    off += n;
                    continue;
                }
                int err = errno(state);
                if (err == eintr()) {
                    continue;
                }
                if (err == eagain()) {
                    pollOut(state);
                    continue;
                }
                return;
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private void pollOut(MemorySegment state) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(8);
            fds.set(ValueLayout.JAVA_INT, 0, fd);
            fds.set(ValueLayout.JAVA_SHORT, 4, (short) pollout());
            fds.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
            MemorySegment st = arena.allocate(CAPTURED);
            int ignored = poll(st, fds, SLICE_MS);
        }
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        applyCooked();
        try {
            if (closeMh != null && fd > 0) {
                int ignored = (int) closeMh.invokeExact(fd);
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private int pollin() {
        return darwin ? TermiosDarwin.POLLIN : TermiosLinux.POLLIN;
    }

    private int pollout() {
        return darwin ? TermiosDarwin.POLLOUT : TermiosLinux.POLLOUT;
    }

    private int eintr() {
        return darwin ? TermiosDarwin.EINTR : TermiosLinux.EINTR;
    }

    private int eagain() {
        return darwin ? TermiosDarwin.EAGAIN : TermiosLinux.EAGAIN;
    }

    private static int errno(MemorySegment state) {
        return (int) ERRNO.get(state, 0L);
    }

    private static void sleepSlice(long nanos) {
        if (nanos <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.max(1L, nanos / 1_000_000L), (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static volatile MethodHandle openMh;
    private static volatile MethodHandle closeMh;
    private static volatile MethodHandle fcntlMh;
    private static volatile MethodHandle tcgetattrMh;
    private static volatile MethodHandle tcsetattrMh;
    private static volatile MethodHandle pollMh;
    private static volatile MethodHandle readMh;
    private static volatile MethodHandle writeMh;
    private static volatile boolean initAttempted;

    @SuppressWarnings("restricted")
    private static void ensure() {
        if (initAttempted) {
            return;
        }
        synchronized (PosixTty.class) {
            if (initAttempted) {
                return;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                Linker.Option cap = Linker.Option.captureCallState("errno");
                Linker.Option variadic2 = Linker.Option.firstVariadicArg(2);
                // Bind independently: native-image throws MissingForeignRegistrationError per
                // descriptor. One missing entry must not leave open/tcgetattr null.
                openMh = bind(
                        linker,
                        lookup,
                        "open",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                closeMh = bind(
                        linker, lookup, "close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                fcntlMh = bind(
                        linker,
                        lookup,
                        "fcntl",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                        variadic2);
                ValueLayout nfds = Os.isDarwin() ? ValueLayout.JAVA_INT : ValueLayout.JAVA_LONG;
                pollMh = bind(
                        linker,
                        lookup,
                        "poll",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, nfds, ValueLayout.JAVA_INT),
                        cap);
                readMh = bind(
                        linker,
                        lookup,
                        "read",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG),
                        cap);
                writeMh = bind(
                        linker,
                        lookup,
                        "write",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG),
                        cap);
                tcgetattrMh = bind(
                        linker,
                        lookup,
                        "tcgetattr",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        cap);
                tcsetattrMh = bind(
                        linker,
                        lookup,
                        "tcsetattr",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        cap);
            } finally {
                initAttempted = true;
            }
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle bind(
            Linker linker, SymbolLookup lookup, String name, FunctionDescriptor desc, Linker.Option... opts) {
        try {
            return linker.downcallHandle(lookup.findOrThrow(name), desc, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("restricted")
    private int poll(MemorySegment state, MemorySegment fds, int timeoutMs) throws Throwable {
        if (Os.isDarwin()) {
            return (int) pollMh.invokeExact(state, fds, 1, timeoutMs);
        }
        return (int) pollMh.invokeExact(state, fds, 1L, timeoutMs);
    }

    @SuppressWarnings("restricted")
    private int readOne(MemorySegment state, MemorySegment buf) throws Throwable {
        long n = (long) readMh.invokeExact(state, fd, buf, 1L);
        return (int) n;
    }

    @SuppressWarnings("restricted")
    private int writeSome(MemorySegment state, MemorySegment buf, int len) throws Throwable {
        long n = (long) writeMh.invokeExact(state, fd, buf, (long) len);
        return (int) n;
    }

    /** Tiny helper so Duration checks stay in one place without a public type. */
    static final class DeadTerminalTimeout {
        private DeadTerminalTimeout() {}

        static void check(Duration timeout) {
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be >= 0");
            }
        }
    }
}
