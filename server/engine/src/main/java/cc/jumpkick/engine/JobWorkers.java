// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request registry of forked worker {@link Process}es (plugin/test JVMs). On job wall deadline
 * the engine {@linkplain #destroyForRequest destroys} registered children so a wedged worker cannot
 * pin the runner (and its {@code cacheGate} read hold) forever (JK-1067).
 *
 * <p>Workers register themselves via {@link #register} when a request scope is open on the calling
 * thread ({@link #open}/{@link #close}).
 */
public final class JobWorkers {

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
     * {@link Process#destroyForcibly()} every process still registered for {@code requestId}.
     * Returns how many were alive when destroy was called.
     */
    public static int destroyForRequest(long requestId) {
        Set<Process> set = BY_REQUEST.remove(requestId);
        if (set == null || set.isEmpty()) return 0;
        int killed = 0;
        for (Process p : set) {
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();
                    killed++;
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        return killed;
    }

    /** Test seam: how many processes are tracked for {@code requestId}. */
    static int trackedCount(long requestId) {
        Set<Process> set = BY_REQUEST.get(requestId);
        return set == null ? 0 : set.size();
    }
}
