// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import org.jspecify.annotations.Nullable;

/**
 * Why a step that has started does no work of its own: the reason {@link TaskContext#waiting} carries.
 * A reason is one of the named ones below, or a short phrase naming what holds the step (a lock and
 * who has it), which a row shows as it stands.
 */
public final class StepWait {

    /** Every worker JVM slot is taken. */
    public static final String SLOT = "slot";

    /** The step's fork does not fit the memory budget. */
    public static final String MEMORY = "memory";

    /** Every engine thread of the step's kind is busy, so its body has not begun. */
    public static final String THREAD = "thread";

    private StepWait() {}

    /** What the step waits for, to follow "waiting for": {@code a free slot}; empty when it works. */
    public static String phrase(@Nullable String reason) {
        if (reason == null || reason.isBlank()) return "";
        return switch (reason) {
            case SLOT -> "a free slot";
            case MEMORY -> "memory";
            case THREAD -> "a free engine thread";
            default -> reason;
        };
    }
}
