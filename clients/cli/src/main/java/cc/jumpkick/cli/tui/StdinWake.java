// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Force-wake a thread blocked in {@code read(0, …)} on the controlling TTY.
 *
 * <p>JLine's NonBlocking reader parks a daemon thread in {@code FileInputStream.read()} on FD 0.
 * On macOS, closing that stream does not interrupt the blocked read, and once ICANON is restored
 * the line discipline only delivers input after newline — so the process appears hung after a
 * successful interactive plan until the user presses Enter (other keys stay in the kernel line
 * buffer). Pulsing {@code O_NONBLOCK} on FD 0 makes the pending {@code read} return immediately
 * ({@code EAGAIN}), which is enough for JLine's I/O thread to leave the kernel wait before
 * cooked mode is restored and before JLine's shutdown closer runs.
 */
final class StdinWake {

    private static final int STDIN_FD = 0;
    private static final int F_GETFL = 3;
    private static final int F_SETFL = 4;

    private static volatile MethodHandle fcntlGet;
    private static volatile MethodHandle fcntlSet;
    private static volatile int oNonblock;
    private static volatile boolean initAttempted;
    private static volatile boolean available;

    private StdinWake() {}

    /**
     * Best-effort: set {@code O_NONBLOCK} on FD 0 briefly so a concurrent blocking {@code read}
     * returns, then restore the previous flags. No-op on Windows or when FFM/fcntl is unavailable.
     */
    static void pulseNonBlocking() {
        ensureInit();
        if (!available) return;
        try {
            int flags = (int) fcntlGet.invokeExact(STDIN_FD, F_GETFL);
            if (flags < 0) return;
            int withNb = flags | oNonblock;
            if (withNb == flags) {
                // Already non-blocking — still touch SETFL so a blocked read can observe a change.
                fcntlSet.invokeExact(STDIN_FD, F_SETFL, flags);
                return;
            }
            if ((int) fcntlSet.invokeExact(STDIN_FD, F_SETFL, withNb) != 0) return;
            // Brief window for the blocked reader to return EAGAIN before we clear the flag.
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Best-effort restore; leave non-blocking if SETFL fails (exiting / tty gone).
            fcntlSet.invokeExact(STDIN_FD, F_SETFL, flags);
        } catch (Throwable ignored) {
            // best-effort only
        }
    }

    @SuppressWarnings("restricted")
    private static void ensureInit() {
        if (initAttempted) return;
        synchronized (StdinWake.class) {
            if (initAttempted) return;
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    available = false;
                    return;
                }
                // Darwin / BSD: O_NONBLOCK = 0x0004. Linux: 04000 (0x800).
                oNonblock = os.contains("mac") || os.contains("darwin") || os.contains("bsd") ? 0x0004 : 0x800;
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                // fcntl(int fd, int cmd) and fcntl(int fd, int cmd, int arg) — two overloads via
                // separate downcalls (variadic third arg would need firstVariadicArg).
                fcntlGet = linker.downcallHandle(
                        lookup.findOrThrow("fcntl"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                fcntlSet = linker.downcallHandle(
                        lookup.findOrThrow("fcntl"),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT));
                available = true;
            } catch (Throwable ignored) {
                available = false;
            } finally {
                initAttempted = true;
            }
        }
    }
}
