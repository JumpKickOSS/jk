// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The {@link TaskContext} of the step running on this thread. A fork waiting on a shared resource
 * reports through it. Carried by {@link ContextPropagator}: a pool task sees the step that
 * submitted it, and a thread started outside one sees none.
 */
public final class StepScope {

    private static final ScopedValue<TaskContext> CURRENT = ScopedValue.newInstance();

    /** Told when a step's scope closes, on the thread that closed it. */
    private static final CopyOnWriteArrayList<Consumer<TaskContext>> ON_CLOSE = new CopyOnWriteArrayList<>();

    private static final AtomicLong CLOSED = new AtomicLong();

    static {
        ContextPropagator.carry(CURRENT);
    }

    private StepScope() {}

    /**
     * Run {@code body} as step {@code ctx}, then close the step's scope: {@link #onClose} listeners
     * are told which step it was, on this thread, once {@code body} has returned or thrown.
     */
    public static <T> T call(TaskContext ctx, Supplier<T> body) {
        try {
            return ScopedValue.where(CURRENT, ctx).call(body::get);
        } finally {
            close(ctx);
        }
    }

    /** As {@link #call} for a body that returns nothing. */
    public static void run(TaskContext ctx, Runnable body) {
        call(ctx, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Run {@code listener} when a step's scope closes. A listener that throws is ignored: closing
     * the scope must not fail the step.
     */
    public static void onClose(Consumer<TaskContext> listener) {
        if (listener != null) ON_CLOSE.addIfAbsent(listener);
    }

    private static void close(TaskContext ctx) {
        CLOSED.incrementAndGet();
        for (Consumer<TaskContext> listener : ON_CLOSE) {
            try {
                listener.accept(ctx);
            } catch (RuntimeException ignored) {
                // a note must not fail the step
            }
        }
    }

    /**
     * How many step scopes have closed in this process. A fact read off build outputs before the
     * count moved may describe outputs a step has since rewritten.
     */
    public static long closed() {
        return CLOSED.get();
    }

    /** The step on this thread, or {@code null} outside one. */
    public static @Nullable TaskContext current() {
        return ContextPropagator.get(CURRENT);
    }
}
