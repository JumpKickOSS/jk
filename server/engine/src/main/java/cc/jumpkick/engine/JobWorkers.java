// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-request registry of forked worker {@link Process}es (plugin/test JVMs). On cancel or job wall
 * deadline the engine shuts them down so a wedged worker cannot pin the runner forever (JK-1067 /
 * JK-1096).
 *
 * <p>Workers register via {@link #register} when a request scope is open ({@link #open}/{@link
 * #close}).
 *
 * <p><strong>Cancel contract (JK-1096):</strong> {@link #shutdownForRequest} first signals
 * cooperative death ({@link Process#destroy destroy} / SIGTERM), waits up to a short grace (default
 * {@value #DEFAULT_CANCEL_GRACE_MS} ms), then {@link Process#destroyForcibly destroyForcibly}. Cancel
 * never hangs waiting for a stuck child. Plugins must treat a sub-second window as all they get to
 * flush state.
 */
public final class JobWorkers {

    /**
     * Default cooperative window before forced kill (JK-1096). Keep small so Ctrl-C / cancel UX is
     * snappy. Override: {@code JK_CANCEL_GRACE_MS}.
     */
    public static final long DEFAULT_CANCEL_GRACE_MS = 500L;

    /**
     * Inheritable so test/plugin worker threads forked from the request runner still attach
     * processes to the same request id.
     */
    private static final InheritableThreadLocal<Long> CURRENT = new InheritableThreadLocal<>();

    private static final ConcurrentHashMap<Long, Set<Process>> BY_REQUEST = new ConcurrentHashMap<>();

    private JobWorkers() {}

    /** Open a request scope on this thread so subsequent {@link #register} calls attach here. */
    public static void open(long requestId) {
        CURRENT.set(requestId);
    }

    /** Drop the thread's request scope (does not kill processes). */
    public static void close() {
        CURRENT.remove();
    }

    /** Forget all processes for {@code requestId} without killing them (scope end after clean exit). */
    public static void clear(long requestId) {
        BY_REQUEST.remove(requestId);
    }

    /** Track {@code process} for the current request, if any. */
    public static void register(Process process) {
        if (process == null) return;
        Long id = CURRENT.get();
        if (id == null) return;
        BY_REQUEST.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(process);
    }

    /** Stop tracking {@code process} (e.g. after it exits normally). */
    public static void unregister(Process process) {
        if (process == null) return;
        Long id = CURRENT.get();
        if (id != null) {
            Set<Process> set = BY_REQUEST.get(id);
            if (set != null) set.remove(process);
            return;
        }
        // Fallback: scan (rare — process ended on a different thread than start).
        for (Set<Process> set : BY_REQUEST.values()) {
            set.remove(process);
        }
    }

    /**
     * Immediate hard kill of every process still registered for {@code requestId} (no grace). Prefer
     * {@link #shutdownForRequest(long, long)} for user cancel. Returns how many were alive when
     * shutdown started.
     */
    public static int destroyForRequest(long requestId) {
        return shutdownForRequest(requestId, 0L);
    }

    /**
     * Shut down workers for {@code requestId} (JK-1096):
     *
     * <ol>
     *   <li>If {@code graceMs > 0}: {@link Process#destroy()} (cooperative / SIGTERM).
     *   <li>Wait up to {@code graceMs} for exit (poll; never blocks longer).
     *   <li>{@link Process#destroyForcibly()} any survivors.
     * </ol>
     *
     * If {@code graceMs <= 0}, skips soft signal and force-kills immediately. Always finishes;
     * never waits unboundedly. Returns how many processes were alive when shutdown began.
     */
    public static int shutdownForRequest(long requestId, long graceMs) {
        Set<Process> set = BY_REQUEST.remove(requestId);
        if (set == null || set.isEmpty()) return 0;
        int aliveAtStart = 0;
        boolean soft = graceMs > 0;
        for (Process p : set) {
            try {
                if (p.isAlive()) {
                    aliveAtStart++;
                    if (soft) p.destroy();
                    else p.destroyForcibly();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        if (soft && aliveAtStart > 0) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
            while (System.nanoTime() < deadlineNanos) {
                if (!anyAlive(set)) break;
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (Process p : set) {
                try {
                    if (p.isAlive()) p.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
        }
        return aliveAtStart;
    }

    private static boolean anyAlive(Set<Process> set) {
        for (Process p : set) {
            try {
                if (p.isAlive()) return true;
            } catch (RuntimeException ignored) {
                // treat as dead
            }
        }
        return false;
    }

    /**
     * Cooperative cancel window for user cancel / Ctrl-C path (JK-1096). Default {@link
     * #DEFAULT_CANCEL_GRACE_MS}; env {@code JK_CANCEL_GRACE_MS} (clamped 0…5000).
     */
    public static long cancelGraceMs() {
        String raw = System.getenv("JK_CANCEL_GRACE_MS");
        if (raw == null || raw.isBlank()) return DEFAULT_CANCEL_GRACE_MS;
        try {
            long n = Long.parseLong(raw.trim());
            if (n < 0) return DEFAULT_CANCEL_GRACE_MS;
            return Math.min(n, 5_000L);
        } catch (NumberFormatException e) {
            return DEFAULT_CANCEL_GRACE_MS;
        }
    }

    /** Test seam: how many processes are tracked for {@code requestId}. */
    static int trackedCount(long requestId) {
        Set<Process> set = BY_REQUEST.get(requestId);
        return set == null ? 0 : set.size();
    }
}
