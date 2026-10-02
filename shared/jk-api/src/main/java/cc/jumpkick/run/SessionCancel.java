// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Seam so steps can observe session-level cancel without a {@code jk-api → core} edge. Engine
 * {@link #bind binds} a probe; {@link DefaultTaskContext#cancelled()} ORs it in. Unbound → false.
 */
public final class SessionCancel {

    private static volatile BooleanSupplier probe = () -> false;

    private static volatile Resumable hold = () -> {};

    /** Waits while the bound session is suspended. */
    @FunctionalInterface
    public interface Resumable {
        void awaitResumed() throws InterruptedException;
    }

    private SessionCancel() {}

    /** Install the session-cancel probe. Idempotent; the last binding wins. A {@code null} disables it. */
    public static void bind(@Nullable BooleanSupplier p) {
        probe = (p == null) ? () -> false : p;
    }

    /** Install the suspend hold. The last binding wins; a {@code null} holds nothing. */
    public static void bindHold(@Nullable Resumable h) {
        hold = (h == null) ? () -> {} : h;
    }

    /** Return once the bound session is not suspended, or is cancelled. */
    public static void awaitResumed() throws InterruptedException {
        hold.awaitResumed();
    }

    /** Whether the bound session (if any) has requested cancellation. */
    public static boolean cancelled() {
        return probe.getAsBoolean();
    }
}
