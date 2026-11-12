// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.Request;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The worker processes one request forked (plugin, test and compiler JVMs), so its cancel or wall
 * deadline can stop them and a wedged worker cannot pin the runner forever. The request carries its
 * scope under {@link #SLOT}, which reaches every pool task and thread it uses; {@link JobWorkers}
 * registers each fork with the scope bound where it starts. A job's scope is also kept by id in the
 * engine's {@link WorkerScopes}, because a cancel arrives with only the id.
 *
 * <p>Once {@link #shutdown} has run the scope is ended: a fork that registers after that — a
 * pool thread still draining after a cancel — is killed on arrival, and a result that arrives under
 * the scope belongs to no report.
 *
 * <p><strong>Cancel contract</strong> {@link #shutdown} signals <em>all</em> live workers first
 * (tight loop — effectively simultaneous), then waits one shared wall-clock grace (the caller's
 * configured shared window for the whole set, not per process), then force-kills survivors, then
 * waits once more — also shared — for those kills to land. Cancel never hangs: both waits are
 * bounded, and a process that outlasts the second one is left to the OS. Plugins must treat that
 * shared sub-second window as all they get.
 *
 * <p>The second wait is what makes "shut down" mean gone rather than told to go. A force-kill
 * returns as soon as the OS accepts it, and until termination finishes the worker still holds its
 * handles — so a caller that kills a worker and deletes the tree it was writing can find that tree
 * undeletable on Windows, at a moment and in a place unrelated to the cancel.
 *
 * <p><strong>Windows:</strong> {@link Process#destroy} is <em>not</em> SIGTERM. On the HotSpot
 * Windows implementation it typically maps to an immediate terminate (similar to {@link
 * Process#destroyForcibly}); there is no portable “ask politely then wait” OS signal. The grace wait
 * still bounds our side of the join; do not rely on Windows workers running shutdown hooks after
 * {@code destroy}. Prefer designing workers so cancel is observed via the session cancel token /
 * stdin EOF where possible, and treat force-kill as the portable last step.
 */
public final class WorkerScope {

    /** The scope a request's forks register to; a request without one registers nothing. */
    public static final Request.Slot<WorkerScope> SLOT = new Request.Slot<>("worker-scope");

    private final long id;
    private final Set<Process> live = ConcurrentHashMap.newKeySet();
    private volatile boolean ended;

    /** Guarded by {@code this}: forks wait while set. */
    private boolean held;

    /**
     * A scope for request {@code id}: a job's id, or a compile scope the engine owns (negative). Its
     * leases are charged to {@code id} too.
     */
    public WorkerScope(long id) {
        this.id = id;
    }

    /** The scope of the request bound now, or {@code null} when none registers forks. */
    public static @Nullable WorkerScope current() {
        Request request = Request.current();
        return request == null ? null : request.get(SLOT);
    }

    public long id() {
        return id;
    }

    /**
     * Run {@code body} with forks registering here: the bound request under this scope's id, or a
     * request that is no job off one.
     */
    public <X extends Throwable> void run(Request.Action<X> body) throws X {
        bound().run(body);
    }

    /** {@link #run} for a body with a result. */
    public <T extends @Nullable Object, X extends Throwable> T call(Request.Body<T, X> body) throws X {
        return bound().call(body);
    }

    private Request bound() {
        return Request.currentOrDetached().withId(id).with(SLOT, this);
    }

    /**
     * True once {@link #shutdown} has run: the request is over, and a result that arrives under it
     * after this — a test pool unblocked by the kill — belongs to no report.
     */
    public boolean ended() {
        return ended;
    }

    /** Forked processes still alive right now. */
    public int liveCount() {
        int n = 0;
        for (Process p : live) if (p.isAlive()) n++;
        return n;
    }

    /** How many processes are tracked, alive or not. */
    int trackedCount() {
        return live.size();
    }

    /** Track {@code process}; once the scope has ended it is killed instead. */
    void register(Process process) {
        if (ended) {
            JobWorkers.forceKillAndAwait(process);
            return;
        }
        live.removeIf(p -> !p.isAlive());
        live.add(process);
        // A shutdown that ended the scope between the check and the add may have missed it: kill it
        // here too. Killing is idempotent, so racing the shutdown's own kill is harmless.
        if (ended) {
            live.remove(process);
            JobWorkers.forceKillAndAwait(process);
        }
    }

    /** Stop tracking {@code process} (after it exits normally). */
    void unregister(Process process) {
        live.remove(process);
    }

    /**
     * Stop ({@code SIGSTOP}) every process this scope forked, children included, and hold its next
     * forks until {@link #release}: a suspended job's workers keep their memory and use no CPU. POSIX
     * only; returns how many processes were stopped.
     */
    public int hold() {
        synchronized (this) {
            held = true;
        }
        return signal("-STOP");
    }

    /** Continue ({@code SIGCONT}) what {@link #hold} stopped and let held forks start. Idempotent. */
    public int release() {
        synchronized (this) {
            if (!held) return 0;
            held = false;
            notifyAll();
        }
        return signal("-CONT");
    }

    /** Wait while this scope is held. */
    void awaitReleased() throws InterruptedIOException {
        synchronized (this) {
            while (held) {
                try {
                    wait(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("cancelled while suspended");
                }
            }
        }
    }

    /** Send {@code signal} to every live process this scope forked and its descendants, in one {@code kill}. */
    private int signal(String signal) {
        if (Os.isWindows()) return 0;
        List<String> command = new ArrayList<>(List.of("kill", signal));
        for (Process p : live) {
            if (!p.isAlive()) continue;
            command.add(Long.toString(p.pid()));
            for (ProcessHandle h : JobWorkers.descendantsOf(p)) command.add(Long.toString(h.pid()));
        }
        int n = command.size() - 2;
        if (n == 0) return 0;
        try {
            Process kill = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            kill.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException e) {
            Log.debug("job " + id + ": kill " + signal + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return n;
    }

    /**
     * End this scope and shut down <em>all</em> its workers
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
    public int shutdown(long graceMs) {
        // A stopped worker would not act on SIGTERM until continued.
        release();
        // End FIRST so a register racing us kills its process on arrival, and drop any lease still
        // queued for this scope so a fork blocked in launch does not outlive the cancel.
        ended = true;
        WorkerLeases.engine().cancelRequest(id);
        List<Process> set = List.copyOf(live);
        live.removeAll(set);
        if (set.isEmpty()) return 0;
        int aliveAtStart = 0;
        boolean soft = graceMs > 0;
        // Snapshot each tree before signalling: after the root dies its descendants() is empty, and
        // phase 3 still has to outlast the grandchildren.
        Map<Process, List<ProcessHandle>> trees = new HashMap<>();
        // Phase 1: signal everyone first (simultaneous for practical purposes), including
        // grandchildren (native-image under a plugin JVM, etc.).
        for (Process p : set) {
            try {
                if (p.isAlive()) {
                    aliveAtStart++;
                    trees.put(p, JobWorkers.descendantsOf(p));
                    JobWorkers.signalTree(p, !soft);
                }
            } catch (RuntimeException e) {
                Log.debug("shutdown: best-effort", e);
            }
        }
        // Phase 2: one shared grace for the whole set, then force leftovers.
        if (soft && aliveAtStart > 0) {
            long deadlineNanos = Clock.SYSTEM.nanos() + TimeUnit.MILLISECONDS.toNanos(graceMs);
            while (Clock.SYSTEM.nanos() < deadlineNanos) {
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
                    if (p.isAlive() || anyDescendantAlive(p)) JobWorkers.signalTree(p, true);
                } catch (RuntimeException e) {
                    Log.debug("shutdown: best-effort", e);
                }
            }
        }
        // Phase 3: one shared wait for the whole set, so "shut down" means gone rather than
        // told to go. Shared, not per process, for the same reason the grace above is.
        if (aliveAtStart > 0) JobWorkers.awaitGone(set, trees);
        return aliveAtStart;
    }

    private static boolean anyAlive(List<Process> set) {
        for (Process p : set) {
            try {
                if (p.isAlive() || anyDescendantAlive(p)) return true;
            } catch (RuntimeException e) {
                Log.debug("anyAlive: treat as dead", e);
            }
        }
        return false;
    }

    private static boolean anyDescendantAlive(Process p) {
        try {
            return p.descendants().anyMatch(ProcessHandle::isAlive);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "WorkerScope[" + id + (ended ? ", ended" : "") + "]";
    }
}
