// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The {@link TaskContext} of the step running on this thread, and the values the step keeps for its
 * life ({@link Local}). A fork waiting on a shared resource reports through it. Carried by {@link
 * ContextPropagator}: a pool task sees the step that submitted it, and a thread started outside one
 * sees none.
 */
public final class StepScope {

    private static final ScopedValue<Step> CURRENT = ScopedValue.newInstance();

    /** Told when a step's scope closes, on the thread that closed it. */
    private static final CopyOnWriteArrayList<Consumer<TaskContext>> ON_CLOSE = new CopyOnWriteArrayList<>();

    private static final AtomicLong CLOSED = new AtomicLong();

    static {
        ContextPropagator.carry(CURRENT);
    }

    /** One run of a step: its context and the locals it has made so far. */
    private record Step(TaskContext ctx, ConcurrentHashMap<Local<?>, Object> locals) {}

    /**
     * A value each step keeps for its life, made on first use and gone with the step's scope.
     * Compared by identity.
     */
    public static final class Local<T> {
        private final Supplier<? extends T> initial;

        public Local(Supplier<? extends T> initial) {
            this.initial = initial;
        }
    }

    /** Work as a step that answers a value and may throw {@code X}. */
    @FunctionalInterface
    public interface Body<T extends @Nullable Object, X extends Throwable> {
        T call() throws X;
    }

    /** Work as a step with no result that may throw {@code X}. */
    @FunctionalInterface
    public interface Action<X extends Throwable> {
        void run() throws X;
    }

    private StepScope() {}

    /**
     * Run {@code body} as step {@code ctx}, then close the step's scope: {@link #onClose} listeners
     * are told which step it was, on this thread, once {@code body} has returned or thrown, while
     * the step's locals are still readable.
     */
    public static <T extends @Nullable Object, X extends Throwable> T call(TaskContext ctx, Body<T, X> body) throws X {
        return ScopedValue.where(CURRENT, new Step(ctx, new ConcurrentHashMap<>()))
                .call(() -> {
                    try {
                        return body.call();
                    } finally {
                        close(ctx);
                    }
                });
    }

    /** As {@link #call} for a body that returns nothing. */
    public static <X extends Throwable> void run(TaskContext ctx, Action<X> body) throws X {
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
        Step step = ContextPropagator.get(CURRENT);
        return step == null ? null : step.ctx();
    }

    /** The step's value for {@code local}, made on first use; {@code null} outside a step. */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable T local(Local<T> local) {
        Step step = ContextPropagator.get(CURRENT);
        if (step == null) return null;
        return (T) step.locals().computeIfAbsent(local, k -> local.initial.get());
    }
}
