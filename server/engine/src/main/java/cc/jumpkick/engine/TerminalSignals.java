// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.host.Log;
import cc.jumpkick.wire.EngineTransport;
import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's terminal-signal policy. SIGINT and SIGHUP are caught by a Java no-op, so the
 * process survives its spawner's terminal while every child it forks starts with the default
 * dispositions; build cancel is a wire concern ({@code CANCEL_REQUEST}), never SIGINT. SIGTERM
 * stays lethal so {@code kill <pid>} works.
 *
 * <p>Order matters. A spawner that had the signals ignored — a background job of a
 * non-interactive shell, {@code nohup} — hands {@code SIG_IGN} down through exec, HotSpot keeps
 * an inherited ignore instead of installing its handler, and every worker, test JVM and sidecar
 * the engine forks would inherit the ignore too: their Ctrl-C would be a no-op. So the native
 * disposition is reset to default first, through the C library's {@code signal(2)}, and only
 * then is the Java handler registered. Every step is best-effort; {@link PosixDetach} already
 * reduces exposure by moving into a new session.
 */
public final class TerminalSignals {

    private TerminalSignals() {}

    /** POSIX signal numbers shared by Linux and macOS. */
    static final int SIGHUP = 1;

    static final int SIGINT = 2;

    /** {@code void (*signal(int sig, void (*handler)(int)))(int)}; a null handler is {@code SIG_DFL}. */
    private static final FunctionDescriptor SIGNAL =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** Linux signal names by number, for the {@code SigIgn} mask of {@code /proc/self/status}. */
    private static final String[] LINUX_NAMES = {
        "HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE", "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM",
        "TERM"
    };

    private static final Path PROC_SELF_STATUS = Path.of("/proc/self/status");

    /** Install the policy: default native dispositions for INT and HUP, then the Java no-op handlers. */
    public static void install() {
        String inherited = ignoredSignals();
        resetToDefault(SIGINT);
        resetToDefault(SIGHUP);
        handleInJava("INT");
        handleInJava("HUP");
        if (!inherited.isEmpty()) {
            Log.info("jk engine: the spawning shell ignored " + inherited
                    + "; dispositions reset so forked workers start with the defaults");
        }
    }

    /**
     * The signals this process ignores right now, as {@code "HUP, INT"}; {@code ""} when none are
     * ignored or the mask is not observable (only Linux publishes it, in {@code /proc/self/status}).
     */
    public static String ignoredSignals() {
        try {
            if (Files.isReadable(PROC_SELF_STATUS)) return ignoredSignals(Files.readString(PROC_SELF_STATUS));
        } catch (IOException | RuntimeException e) {
            Log.debug("ignoredSignals: best-effort", e);
        }
        return "";
    }

    /** The {@code SigIgn} line of a {@code /proc/<pid>/status} text, decoded to names; {@code ""} when clear. */
    static String ignoredSignals(String procStatus) {
        for (String line : procStatus.split("\n")) {
            if (!line.startsWith("SigIgn:")) continue;
            long mask =
                    Long.parseUnsignedLong(line.substring("SigIgn:".length()).trim(), 16);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < LINUX_NAMES.length; i++) {
                if ((mask & (1L << i)) != 0) names.add(LINUX_NAMES[i]);
            }
            return String.join(", ", names);
        }
        return "";
    }

    /** {@code signal(sig, SIG_DFL)} through the FFM linker; POSIX only. */
    private static void resetToDefault(int sig) {
        if (EngineTransport.useLoopbackTcp()) return; // Windows: no POSIX dispositions to inherit
        try {
            Linker linker = Linker.nativeLinker();
            MemorySegment addr = linker.defaultLookup().find("signal").orElse(null);
            if (addr == null) return;
            MethodHandle signal = linker.downcallHandle(addr, SIGNAL);
            MemorySegment unusedPrevious = (MemorySegment) signal.invokeExact(sig, MemorySegment.NULL);
        } catch (Throwable t) {
            Log.debug("resetToDefault: best-effort", t);
        }
    }

    private static void handleInJava(String name) {
        try {
            // HotSpot: sun.misc.Signal. Not on every runtime; best-effort only.
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance(name);
            Object handler = Proxy.newProxyInstance(
                    handlerClass.getClassLoader(), new Class<?>[] {handlerClass}, (proxy, method, args) -> null);
            signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.debug("handleInJava: best-effort", e);
        }
    }
}
