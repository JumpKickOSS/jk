// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.function.BooleanSupplier;

/**
 * Seam so steps can observe session-level cancel without a {@code jk-api → core} edge. Engine
 * {@link #bind binds} a probe; {@link DefaultStepContext#cancelled()} ORs it in. Unbound → false.
 */
public final class SessionCancel {

    private static volatile BooleanSupplier probe = () -> false;

    private SessionCancel() {}

    /** Install the session-cancel probe. Idempotent; the last binding wins. A {@code null} disables it. */
    public static void bind(BooleanSupplier p) {
        probe = (p == null) ? () -> false : p;
    }

    /** Whether the bound session (if any) has requested cancellation. */
    public static boolean cancelled() {
        return probe.getAsBoolean();
    }
}
