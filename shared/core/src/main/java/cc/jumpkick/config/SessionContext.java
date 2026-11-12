// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.run.ContextPropagator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Ambient holder for the current {@link Session} and {@link Request}. {@link #current()} prefers a
 * per-thread {@link ScopedValue} binding ({@link #where}/{@link #runWhere}) so concurrent in-JVM
 * builds each see their own session; then the session of the enclosing CLI invocation
 * ({@link #invocation}); otherwise a process-wide static.
 *
 * <p>One {@link ScopedValue} carries both the session and the request, registered with {@link
 * ContextPropagator} so that binding follows work onto {@link cc.jumpkick.run.JkThreads} workers and
 * onto the threads {@link #startVirtual}/{@link #startPlatform} start. A bare thread sees
 * neither: it reads the invocation's or the static session and no request.
 *
 * <p>{@link #install}/{@link #installConfig} write the invocation's session when one is open, so
 * two invocations in one JVM never see each other's flags, and the static fallback otherwise;
 * {@link #reset} touches only the static. A {@code where} binding is immutable for the life of its
 * scope — nest {@code where} to change it. Engine code should take an explicit {@code Session}
 * parameter rather than ambient lookup.
 */
public final class SessionContext {

    /**
     * The session and request bound on this thread. A {@code null} session is a request bound without
     * pinning one: {@link #current()} then falls through to the invocation's or the static session.
     */
    private record Bound(
            @Nullable Session session, @Nullable Request request) {}

    private static final ScopedValue<Bound> SCOPED = ScopedValue.newInstance();

    /** One CLI invocation's session and facts, bound for that invocation's extent. */
    private static final ScopedValue<Invocation> INVOCATION = ScopedValue.newInstance();

    /** The session an invocation installs into, and what it read once for its whole extent. */
    private record Invocation(AtomicReference<Session> session, ConcurrentMap<Object, Object> facts) {
        Invocation() {
            this(new AtomicReference<>(Session.defaults()), new ConcurrentHashMap<>());
        }
    }

    /** Process-wide fallback for code outside any invocation or binding. */
    private static volatile Session current = Session.defaults();

    static {
        // Carried pinned: a worker reads the submitter's session even when it came from the
        // invocation or the static, and a submitter off a request hands the worker no request.
        ContextPropagator.carry(SCOPED, SessionContext::capture);
    }

    /** The calling thread's session, pinned, and its request: what a hop to another thread carries. */
    private static Bound capture() {
        return new Bound(current(), request());
    }

    private SessionContext() {}

    /** Install {@code session} for the open invocation, or onto the process-static fallback. */
    public static void install(Session session) {
        Session s = (session == null) ? Session.defaults() : session;
        if (INVOCATION.isBound()) INVOCATION.get().session().set(s);
        else current = s;
    }

    /**
     * Run {@code body} as one CLI invocation: it starts from {@link Session#defaults()}, and what it
     * installs is its own, invisible to any other invocation running in the same JVM.
     */
    public static <T> T invocation(Callable<T> body) throws Exception {
        return ScopedValue.where(INVOCATION, new Invocation()).<T, Exception>call(body::call);
    }

    /**
     * {@code compute}'s value for {@code key}, read once per CLI invocation: every later ask in the
     * same invocation gets the first answer until its {@link #nextPass}, and the next invocation in the
     * same JVM reads afresh.
     * Outside an invocation it is computed on every ask, which is uncached but never stale. Two
     * threads racing on one key may both compute; the first value stored is the one every caller
     * gets.
     */
    @SuppressWarnings("unchecked")
    public static <T> T perInvocation(Object key, Supplier<T> compute) {
        if (!INVOCATION.isBound()) return compute.get();
        ConcurrentMap<Object, Object> facts = INVOCATION.get().facts();
        Object known = facts.get(key);
        if (known != null) return (T) known;
        T computed = compute.get();
        Object prior = facts.putIfAbsent(key, computed);
        return prior != null ? (T) prior : computed;
    }

    /**
     * The open invocation starts its next pass: every {@link #perInvocation} fact is read again on its
     * next ask. A long-lived command ({@code jk watch}, {@code jk dev}) is one invocation for its whole
     * life, and each change it rebuilds for is a pass; without this, a keychain login rotated or
     * removed while it runs would not reach the requests it sends. Outside an invocation there is
     * nothing to forget.
     */
    public static void nextPass() {
        if (INVOCATION.isBound()) INVOCATION.get().facts().clear();
    }

    /** Convenience: install the config slice onto the current session (keeps the other fields). */
    public static void installConfig(JkConfig config) {
        install(current().withConfig(config == null ? JkConfig.empty() : config));
    }

    /**
     * The current session (never null; {@link Session#defaults()} before install). Prefers the
     * {@link ScopedValue} binding of the calling thread when one is bound, then the open invocation's
     * session, else the process static.
     */
    public static Session current() {
        if (SCOPED.isBound()) {
            Session s = SCOPED.get().session();
            if (s != null) return s;
        }
        return INVOCATION.isBound() ? INVOCATION.get().session().get() : current;
    }

    /** The request bound on this thread, or {@code null}; read through {@link Request#current()}. */
    static @Nullable Request request() {
        return SCOPED.isBound() ? SCOPED.get().request() : null;
    }

    /** Run {@code body} with {@code request} bound, keeping the session bound now; see {@link Request#call}. */
    static <T extends @Nullable Object, X extends Throwable> T bind(Request request, Request.Body<T, X> body) throws X {
        Session pinned = SCOPED.isBound() ? SCOPED.get().session() : null;
        return ScopedValue.where(SCOPED, new Bound(pinned, request)).call(body::call);
    }

    /**
     * Run {@code body} with {@code s} bound as the current session for the dynamic extent of the call
     * (and any threads it structurally forks). {@link #current()} returns {@code s} within that scope;
     * the request bound now stays bound.
     */
    public static <T> T where(Session s, Callable<T> body) throws Exception {
        // Java 25's finalized Carrier.call takes a ScopedValue.CallableOp; adapt the Callable via a
        // method reference (Callable.call and CallableOp.call share the R call() throws X shape).
        return ScopedValue.where(SCOPED, new Bound(s, request())).<T, Exception>call(body::call);
    }

    /** Void-returning variant of {@link #where(Session, java.util.concurrent.Callable)}. */
    public static void runWhere(Session s, Runnable body) {
        ScopedValue.where(SCOPED, new Bound(s, request())).run(body);
    }

    /**
     * Start a virtual thread that runs {@code body} under the calling thread's session, request and
     * every other {@link ContextPropagator carried} value. A bare {@code Thread.ofVirtual().start}
     * carries no {@link ScopedValue} binding, so code on it
     * reads the process default and no request — a worker JVM forked from such a thread is sized and
     * flagged without the request's tuning, runs without its cancel token, and is not the request's
     * to kill on cancel.
     */
    public static Thread startVirtual(String name, Runnable body) {
        return Thread.ofVirtual().name(name).start(ContextPropagator.wrapRunnable(body));
    }

    /**
     * As {@link #startVirtual}, on a daemon platform thread: for a body that must run whatever the
     * virtual-thread scheduler is busy with — a reader of a worker's pipe, whose carrier a saturated
     * scheduler would otherwise hand to the work that saturates it.
     */
    public static Thread startPlatform(String name, Runnable body) {
        return Thread.ofPlatform().daemon().name(name).start(ContextPropagator.wrapRunnable(body));
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
