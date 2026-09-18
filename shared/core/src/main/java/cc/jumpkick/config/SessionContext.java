// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.task.IoLedger;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * Ambient holder for the current {@link Session}. {@link #current()} prefers a per-thread
 * {@link ScopedValue} binding ({@link #where}/{@link #runWhere}) so concurrent in-JVM builds each
 * see their own session; otherwise falls back to a process-wide static ({@link #install}).
 *
 * <p>{@link #install}/{@link #installConfig}/{@link #reset} mutate only the static fallback;
 * a {@code ScopedValue} binding is immutable for the life of its scope — nest {@code where} to
 * change it. Engine code should take an explicit {@code Session} parameter rather than ambient
 * lookup.
 */
public final class SessionContext {

    /** Per-thread binding set by {@link #where}/{@link #runWhere}; enables concurrent in-JVM builds. */
    private static final ScopedValue<Session> SCOPED = ScopedValue.newInstance();

    /** Process-wide fallback for the single-build CLI path. */
    private static volatile Session current = Session.defaults();

    static {
        // Propagate where()-bound sessions (and their cancel tokens) onto JkThreads pool workers;
        // ScopedValue does not reach pre-existing shared executors without this. The run ledger
        // rides along for the reason in withLedger. Added beside, not bound over, whatever another
        // subsystem registered first: class-initialization order must not decide which context
        // survives the hop.
        ContextPropagator.add(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                Session s = current();
                IoLedger run = IoLedger.ambient();
                return () -> runWithLedger(run, () -> runWhere(s, r));
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                Session s = current();
                IoLedger run = IoLedger.ambient();
                return () -> withLedger(run, () -> where(s, c));
            }
        });
    }

    /**
     * Run {@code body} with {@code run} as the worker thread's ambient ledger, restoring whatever was
     * there before.
     *
     * <p><strong>Why the ledger has to be propagated and not inherited.</strong> {@code IoLedger}'s
     * ambient holder is an {@code InheritableThreadLocal}, and inheritance is a snapshot taken once,
     * when a thread is <em>created</em>. {@link cc.jumpkick.run.JkThreads#cpu()} is a process-wide
     * {@code ForkJoinPool} whose workers are created lazily — on demand, inside whichever request
     * first widened the pool. Those workers then live for the engine's life still holding that first
     * request's ledger, and every later request's CPU step read it back as "the ambient request".
     *
     * <p>That is not merely mis-billed bytes. {@link RequestScope} keys on the ledger instance
     * precisely because a ledger cannot outlive its request — so a leaked one handed every
     * subsequent build the <em>first</em> build's derived facts. A module that had no {@code
     * src/test/java} when the pool warmed had that scan memoized empty, and {@code jk test} then
     * reported "no test sources" — a green run — for every test written afterwards, until the engine
     * restarted.
     *
     * <p>Capturing on the submitting thread and binding here makes the worker's ledger the one
     * belonging to the request that submitted the task. A {@code null} capture (a caller off a
     * request) <em>clears</em> rather than leaves, so an inherited ledger cannot be mistaken for an
     * ambient request either: {@code RequestScope} then answers "no request" and recomputes, which is
     * uncached but never stale.
     */
    private static <T> T withLedger(@Nullable IoLedger run, Callable<T> body) throws Exception {
        IoLedger previous = IoLedger.ambient();
        IoLedger.open(run);
        try {
            return body.call();
        } finally {
            IoLedger.open(previous);
        }
    }

    /** Void-returning variant of {@link #withLedger}, for the {@code Runnable} hop. */
    private static void runWithLedger(@Nullable IoLedger run, Runnable body) {
        IoLedger previous = IoLedger.ambient();
        IoLedger.open(run);
        try {
            body.run();
        } finally {
            IoLedger.open(previous);
        }
    }

    private SessionContext() {}

    /** Install the resolved session onto the process-static fallback for this invocation. */
    public static void install(Session session) {
        current = (session == null) ? Session.defaults() : session;
    }

    /** Convenience: install the config slice onto the current session (keeps the other fields). */
    public static void installConfig(JkConfig config) {
        install(current().withConfig(config == null ? JkConfig.empty() : config));
    }

    /**
     * The current session (never null; {@link Session#defaults()} before install). Prefers the
     * {@link ScopedValue} binding of the calling thread when one is bound, else the process static.
     */
    public static Session current() {
        return SCOPED.isBound() ? SCOPED.get() : current;
    }

    /**
     * Run {@code body} with {@code s} bound as the current session for the dynamic extent of the call
     * (and any threads it structurally forks). {@link #current()} returns {@code s} within that scope.
     */
    public static <T> T where(Session s, Callable<T> body) throws Exception {
        // Java 25's finalized Carrier.call takes a ScopedValue.CallableOp; adapt the Callable via a
        // method reference (Callable.call and CallableOp.call share the R call() throws X shape).
        return ScopedValue.where(SCOPED, s).<T, Exception>call(body::call);
    }

    /** Void-returning variant of {@link #where(Session, java.util.concurrent.Callable)}. */
    public static void runWhere(Session s, Runnable body) {
        ScopedValue.where(SCOPED, s).run(body);
    }

    /**
     * Start a virtual thread that runs {@code body} under the calling thread's session. A bare
     * {@code Thread.ofVirtual().start} carries no {@link ScopedValue} binding, so code on it reads
     * the process default — a worker JVM forked from such a thread is sized and flagged without the
     * request's tuning and runs without its cancel token.
     */
    public static Thread startVirtual(String name, Runnable body) {
        Session session = current();
        return Thread.ofVirtual().name(name).start(() -> runWhere(session, body));
    }

    /**
     * As {@link #startVirtual}, on a daemon platform thread: for a body that must run whatever the
     * virtual-thread scheduler is busy with — a reader of a worker's pipe, whose carrier a saturated
     * scheduler would otherwise hand to the work that saturates it.
     */
    public static Thread startPlatform(String name, Runnable body) {
        Session session = current();
        return Thread.ofPlatform().daemon().name(name).start(() -> runWhere(session, body));
    }

    /**
     * The process-static fallback itself, ignoring any {@link ScopedValue} binding on the calling
     * thread. Test support: {@link #current()} prefers the binding, so snapshotting it and writing
     * it back with {@link #install} publishes one thread's session to every other. Snapshot this
     * when the static's own prior value is what has to be restored.
     */
    public static Session installed() {
        return current;
    }

    /** Reset the process-static fallback to defaults. Primarily for tests that share a JVM. */
    public static void reset() {
        current = Session.defaults();
    }
}
