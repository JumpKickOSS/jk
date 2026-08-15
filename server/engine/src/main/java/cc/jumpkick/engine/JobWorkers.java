// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Per-request registry of forked worker {@link Process}es (plugin/test JVMs). On cancel or job wall
 * deadline the engine shuts them down so a wedged worker cannot pin the runner forever /
 *
 *
 * <p>Workers register via {@link #register} when a request scope is open ({@link #open}/{@link
 * #close}).
 *
 * <p><strong>Cancel contract</strong> {@link #shutdownForRequest} signals <em>all</em>
 * live workers first (tight loop — effectively simultaneous), then waits one shared wall-clock
 * grace (default {@value #DEFAULT_CANCEL_GRACE_MS} ms for the whole set, not per process), then
 * force-kills survivors. Cancel never hangs. Plugins must treat that shared sub-second window as
 * all they get.
 *
 * <p><strong>Windows:</strong> {@link Process#destroy} is <em>not</em> SIGTERM. On the HotSpot
 * Windows implementation it typically maps to an immediate terminate (similar to
 * {@link Process#destroyForcibly}); there is no portable “ask politely then wait” OS signal.
 * The grace wait still bounds our side of the join; do not rely on Windows workers running
 * shutdown hooks after {@code destroy}. Prefer designing workers so cancel is observed via the
 * session cancel token / stdin EOF where possible, and treat force-kill as the portable last step.
 */
public final class JobWorkers {

    /**
     * Default <strong>shared</strong> wall-clock grace for the whole worker set. Not
     * per-worker and not additive. Override: {@code JK_CANCEL_GRACE_MS} (clamped 0…{@link
     * #MAX_CANCEL_GRACE_MS} so a mistaken env cannot reintroduce multi-second wedged UX).
     */
    public static final long DEFAULT_CANCEL_GRACE_MS = 500L;

    /**
     * Absolute ceiling for {@code JK_CANCEL_GRACE_MS}. Only a misconfiguration safety clamp — product
     * default remains {@link #DEFAULT_CANCEL_GRACE_MS}. Not “workers may take 5 s each.”
     */
    public static final long MAX_CANCEL_GRACE_MS = 5_000L;

    /**
     * The request a fork on this thread belongs to.
     *
     * <p>Inheritable so a thread the request runner creates itself still attaches to the same
     * request. Inheritance alone is <em>not</em> enough, though: CPU steps (compile-java,
     * compile-kotlin, plugin-*) run on {@code JkThreads.cpu()}, a process-wide ForkJoinPool whose
     * threads inherit whatever scope happened to be open when the pool first created them — so a
     * javac forked for request 7 could land under request 1 (or nowhere), and cancel would never
     * kill it. The propagator registered below carries the submitting thread's scope across that
     * pool hop, which is what makes the cancel contract in the class javadoc actually hold.
     */
    private static final InheritableThreadLocal<Long> CURRENT = new InheritableThreadLocal<>();

    private static final ConcurrentHashMap<Long, Set<Process>> BY_REQUEST = new ConcurrentHashMap<>();

    static {
        // SessionContext's static init uses bind() (displaces); force it to land before our add().
        cc.jumpkick.config.SessionContext.current();
        cc.jumpkick.run.ContextPropagator.add(new cc.jumpkick.run.ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                Long captured = CURRENT.get();
                return () -> runWithScope(captured, r);
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                Long captured = CURRENT.get();
                return () -> {
                    Long previous = CURRENT.get();
                    setScope(captured);
                    try {
                        return c.call();
                    } finally {
                        setScope(previous);
                    }
                };
            }
        });
    }

    private static void runWithScope(Long captured, Runnable r) {
        Long previous = CURRENT.get();
        setScope(captured);
        try {
            r.run();
        } finally {
            setScope(previous);
        }
    }

    private static void setScope(Long id) {
        if (id == null) CURRENT.remove();
        else CURRENT.set(id);
    }

    private JobWorkers() {}

    /** Open a request scope on this thread so subsequent {@link #register} calls attach here. */
    public static void open(long requestId) {
        CURRENT.set(requestId);
    }

    /** Drop the thread's request scope (does not kill processes). */
    public static void close() {
        CURRENT.remove();
    }

    /** Test seam: the request scope currently open on this thread, or {@code null}. */
    static Long currentScope() {
        return CURRENT.get();
    }

    /** Test seam: force the shared CPU pool's threads to exist under the caller's scope. */
    static void warmPoolForTest() throws Exception {
        int n = Math.max(2, Runtime.getRuntime().availableProcessors());
        List<Future<?>> pending = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pending.add(cc.jumpkick.run.JkThreads.cpu().submit(() -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        release.countDown();
        for (var f : pending) f.get(10, TimeUnit.SECONDS);
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
     * Shut down <em>all</em> workers for {@code requestId}
     *
     * <ol>
     * <li>If {@code graceMs > 0}: {@link Process#destroy} on <strong>every</strong> live process
     * first (tight loop — one shared signal phase; not staggered per worker).
     * <li>Wait up to {@code graceMs} <strong>once</strong> for the set to exit (shared wall clock;
     * early exit if all dead). Never {@code graceMs × N}.
     * <li>{@link Process#destroyForcibly} any survivors.
     * </ol>
     *
     * If {@code graceMs <= 0}, force-kills immediately. Always finishes; never waits unboundedly.
     * Returns how many processes were alive when shutdown began.
     *
     * <p>On Windows, step 1 may already be terminal (no SIGTERM); step 2 still bounds our wait.
     */
    public static int shutdownForRequest(long requestId, long graceMs) {
        Set<Process> set = BY_REQUEST.remove(requestId);
        if (set == null || set.isEmpty()) return 0;
        int aliveAtStart = 0;
        boolean soft = graceMs > 0;
        // Phase 1: signal everyone first (simultaneous for practical purposes).
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
        // Phase 2: one shared grace for the whole set, then force leftovers.
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
     * Shared wall-clock cancel grace for the whole worker set. Default {@link
     * #DEFAULT_CANCEL_GRACE_MS}; env {@code JK_CANCEL_GRACE_MS} clamped to {@code 0}…{@link
     * #MAX_CANCEL_GRACE_MS} so a typo cannot restore multi-second wedged UX.
     */
    public static long cancelGraceMs() {
        String raw = System.getenv("JK_CANCEL_GRACE_MS");
        if (raw == null || raw.isBlank()) return DEFAULT_CANCEL_GRACE_MS;
        try {
            long n = Long.parseLong(raw.trim());
            if (n < 0) return DEFAULT_CANCEL_GRACE_MS;
            return Math.min(n, MAX_CANCEL_GRACE_MS);
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
