// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.run.ContextPropagator;
import java.util.concurrent.Callable;

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
        // ScopedValue does not reach pre-existing shared executors without this.
        ContextPropagator.bind(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                Session s = current();
                return () -> runWhere(s, r);
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                Session s = current();
                return () -> where(s, c);
            }
        });
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
