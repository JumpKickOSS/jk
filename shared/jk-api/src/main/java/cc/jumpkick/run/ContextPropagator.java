// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.Callable;

/**
 * Seam that re-binds ambient session context on {@link JkThreads} workers ({@code ScopedValue}
 * does not propagate to shared executors). Core binds a {@link Propagator}; until then
 * {@link #IDENTITY} is a no-op. Avoids a {@code jk-api → core} compile cycle.
 */
public final class ContextPropagator {

    /**
     * Wraps a pool task so ambient context captured on the submitting thread is re-established on the
     * worker thread. {@code wrap*} is invoked <b>on the submitting thread</b> (capturing the ambient
     * context now) and returns a task that restores that context when later run on a worker.
     */
    public interface Propagator {

        /** Capture context now (submitting thread); return a Runnable that restores it when run. */
        Runnable wrapRunnable(Runnable r);

        /** Capture context now (submitting thread); return a Callable that restores it when called. */
        <T> Callable<T> wrapCallable(Callable<T> c);
    }

    /** Pass-through propagator: tasks are returned unchanged (unbound / default behavior). */
    public static final Propagator IDENTITY = new Propagator() {
        @Override
        public Runnable wrapRunnable(Runnable r) {
            return r;
        }

        @Override
        public <T> Callable<T> wrapCallable(Callable<T> c) {
            return c;
        }
    };

    private static volatile Propagator active = IDENTITY;

    private ContextPropagator() {}

    /** Install the context-capturing propagator. Last binding wins; a {@code null} resets to identity. */
    public static void bind(Propagator p) {
        active = (p == null) ? IDENTITY : p;
    }

    /** Wrap a Runnable via the active propagator (called on the submitting thread). */
    public static Runnable wrapRunnable(Runnable r) {
        return active.wrapRunnable(r);
    }

    /** Wrap a Callable via the active propagator (called on the submitting thread). */
    public static <T> Callable<T> wrapCallable(Callable<T> c) {
        return active.wrapCallable(c);
    }
}
