// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The {@link TaskContext} of the step running on this thread. A fork waiting on a shared resource
 * reports through it. Pool workers do not inherit it: a pool thread is created once, under
 * whichever step first needed it, so the hop captures the submitting step and clears a worker that
 * has none.
 */
public final class StepScope {

    private static final InheritableThreadLocal<TaskContext> CURRENT = new InheritableThreadLocal<>();

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

    /** Drop this thread's step. */
    public static void close() {
        CURRENT.remove();
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
