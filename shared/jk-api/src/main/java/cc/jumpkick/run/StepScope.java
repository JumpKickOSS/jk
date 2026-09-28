// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The {@link TaskContext} of the step running on this thread. A fork waiting on a shared resource
 * reports through it. Pool workers do not inherit it: a pool thread is created once, under
 * whichever step first needed it, so the hop captures the submitting step and clears a worker that
 * has none.
 */
public final class StepScope {

    private static final InheritableThreadLocal<TaskContext> CURRENT = new InheritableThreadLocal<>();

    /** Told when a step's scope closes, on the thread that closed it. */
    private static final CopyOnWriteArrayList<Consumer<TaskContext>> ON_CLOSE = new CopyOnWriteArrayList<>();

    static {
        ContextPropagator.add(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                TaskContext captured = CURRENT.get();
                return () -> run(captured, r);
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                TaskContext captured = CURRENT.get();
                return () -> {
                    TaskContext previous = CURRENT.get();
                    open(captured);
                    try {
                        return c.call();
                    } finally {
                        open(previous);
                    }
                };
            }
        });
    }

    private StepScope() {}

    /** Bind {@code ctx} for this thread. {@code null} clears it. */
    public static void open(@Nullable TaskContext ctx) {
        if (ctx == null) CURRENT.remove();
        else CURRENT.set(ctx);
    }

    /**
     * Run {@code listener} when a step's scope closes. A listener that throws is ignored: closing
     * the scope must not fail the step.
     */
    public static void onClose(Consumer<TaskContext> listener) {
        if (listener != null) ON_CLOSE.addIfAbsent(listener);
    }

    /** Drop this thread's step and tell {@link #onClose} listeners which step it was. */
    public static void close() {
        TaskContext ctx = CURRENT.get();
        CURRENT.remove();
        if (ctx == null) return;
        for (Consumer<TaskContext> listener : ON_CLOSE) {
            try {
                listener.accept(ctx);
            } catch (RuntimeException ignored) {
                // a note must not fail the step
            }
        }
    }

    /** The step on this thread, or {@code null} outside one. */
    public static @Nullable TaskContext current() {
        return CURRENT.get();
    }

    private static void run(@Nullable TaskContext captured, Runnable body) {
        TaskContext previous = CURRENT.get();
        open(captured);
        try {
            body.run();
        } finally {
            open(previous);
        }
    }
}
