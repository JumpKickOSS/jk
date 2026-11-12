// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ScopedValue}s that follow work off the thread that submits it. A {@code ScopedValue}
 * binding does not reach a pre-existing pool's threads, so {@link JkThreads} wraps every task, and
 * {@code SessionContext.startVirtual}/{@code startPlatform} every thread they start, in one
 * {@link ScopedValue.Carrier} snapshotted on the submitting thread.
 *
 * <p>Each owner registers its value once with {@link #carry}. The snapshot binds every registered
 * value, an unbound one to {@code null}, so a task sees exactly its submitter's values whatever
 * thread runs it, including one that is running another task's. A carried value is therefore read
 * through {@link #get}, which treats a {@code null} binding as unbound.
 */
public final class ContextPropagator {

    /** One carried value and how its value is read on the submitting thread. */
    private record Carried<T>(ScopedValue<T> value, Supplier<@Nullable T> capture) {
        ScopedValue.Carrier bind(ScopedValue.@Nullable Carrier into) {
            T now = capture.get();
            return into == null ? ScopedValue.where(value, now) : into.where(value, now);
        }
    }

    private static final List<Carried<?>> CARRIED = new CopyOnWriteArrayList<>();

    private ContextPropagator() {}

    /** Carry {@code value} as bound on the submitting thread. Registering one value twice is a no-op. */
    public static <T> void carry(ScopedValue<T> value) {
        carry(value, () -> get(value));
    }

    /**
     * Carry {@code value} as {@code capture} reads it on the submitting thread: for an owner whose
     * carried binding is derived, such as a session pinned from the invocation's fallback.
     */
    public static synchronized <T> void carry(ScopedValue<T> value, Supplier<@Nullable T> capture) {
        for (Carried<?> c : CARRIED) {
            if (c.value() == value) return;
        }
        CARRIED.add(new Carried<>(value, capture));
    }

    /** {@code value}'s binding on this thread, or {@code null} when it is unbound or carried as unbound. */
    public static <T> @Nullable T get(ScopedValue<T> value) {
        return value.isBound() ? value.get() : null;
    }

    /** Every carried value as this thread holds it now, or {@code null} when none is registered. */
    private static ScopedValue.@Nullable Carrier snapshot() {
        ScopedValue.Carrier carrier = null;
        for (Carried<?> c : CARRIED) carrier = c.bind(carrier);
        return carrier;
    }

    /** {@code r}, run under this thread's carried values wherever it later runs. */
    public static Runnable wrapRunnable(Runnable r) {
        ScopedValue.Carrier carrier = snapshot();
        return carrier == null ? r : () -> carrier.run(r);
    }

    /** {@code c}, called under this thread's carried values wherever it is later called. */
    public static <T> Callable<T> wrapCallable(Callable<T> c) {
        ScopedValue.Carrier carrier = snapshot();
        return carrier == null ? c : () -> carrier.<T, Exception>call(c::call);
    }
}
