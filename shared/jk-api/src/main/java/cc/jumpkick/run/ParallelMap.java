// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A function over a list on {@link JkThreads#cpu()}, results in input order. The caller takes items
 * too, and the helpers it hands to the pool carry its {@link ContextPropagator carried values}, so
 * the work sees the caller's request and step whichever thread runs it. Short lists stay on the
 * calling thread, where a fan-out costs more than it saves.
 */
public final class ParallelMap {

    /** Below this many items {@link #map(List, Fn)} stays on the calling thread: for cheap items. */
    public static final int THRESHOLD = 64;

    /** A function that may throw {@code X}. */
    @FunctionalInterface
    public interface Fn<T, R extends @Nullable Object, X extends Exception> {
        R apply(T t) throws X;
    }

    /** An action on one item that may throw {@code X}. */
    @FunctionalInterface
    public interface Action<T, X extends Exception> {
        void accept(T t) throws X;
    }

    private ParallelMap() {}

    /** {@code fn} over {@code items}, in order, fanned out from {@link #THRESHOLD} items; the first failure is rethrown. */
    public static <T, R extends @Nullable Object, X extends Exception> List<R> map(List<T> items, Fn<T, R, X> fn)
            throws X {
        return map(items, THRESHOLD, fn);
    }

    /**
     * {@code fn} over {@code items}, in order, fanned out once there are {@code threshold} of them:
     * a low threshold for items that each cost a file read or more. The first failure is rethrown
     * as itself and stops the items not yet started.
     */
    public static <T, R extends @Nullable Object, X extends Exception> List<R> map(
            List<T> items, int threshold, Fn<T, R, X> fn) throws X {
        int n = items.size();
        if (n < Math.max(2, threshold)) {
            List<R> out = new ArrayList<>(n);
            for (T t : items) out.add(fn.apply(t));
            return out;
        }
        Object[] out = new Object[n];
        new Run<>(items, fn, out).fanOut();
        @SuppressWarnings("unchecked")
        List<R> results = (List<R>) Collections.unmodifiableList(Arrays.asList(out));
        return results;
    }

    /** {@code action} on every item, fanned out once there are {@code threshold} of them. */
    public static <T, X extends Exception> void forEach(List<T> items, int threshold, Action<T, X> action) throws X {
        map(items, threshold, t -> {
            action.accept(t);
            return null;
        });
    }

    /**
     * One fan-out: the caller and up to {@link JkThreads#CPU_THREADS} − 1 helpers take the next item
     * until none is left. A helper that starts after the caller ran out does nothing, so the caller
     * waits only for helpers already working, never for one still queued behind it.
     */
    private static final class Run<T, R extends @Nullable Object, X extends Exception> {
        private final List<T> items;
        private final Fn<T, R, X> fn;
        private final @Nullable Object[] out;
        private final AtomicInteger next = new AtomicInteger();
        private int active;
        private boolean closed;
        private @Nullable Throwable failure;

        Run(List<T> items, Fn<T, R, X> fn, @Nullable Object[] out) {
            this.items = items;
            this.fn = fn;
            this.out = out;
        }

        @SuppressWarnings("unchecked")
        void fanOut() throws X {
            int helpers = Math.min(JkThreads.CPU_THREADS, items.size()) - 1;
            for (int i = 0; i < helpers; i++) JkThreads.cpu().execute(this::help);
            take();
            boolean interrupted = false;
            synchronized (this) {
                closed = true;
                while (active > 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            Throwable t = failure;
            if (t == null) return;
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw (X) t;
        }

        private void help() {
            synchronized (this) {
                if (closed) return;
                active++;
            }
            try {
                take();
            } finally {
                synchronized (this) {
                    if (--active == 0) notifyAll();
                }
            }
        }

        private void take() {
            int n = items.size();
            for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) {
                try {
                    out[i] = fn.apply(items.get(i));
                } catch (Throwable t) {
                    synchronized (this) {
                        if (failure == null) failure = t;
                    }
                    next.set(n);
                    return;
                }
            }
        }
    }
}
