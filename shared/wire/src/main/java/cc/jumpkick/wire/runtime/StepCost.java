// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.Objects;

/**
 * One step a module is priced to run, in {@link WorkSchedule} weight units (ms over {@link
 * WorkModel#MS_PER_WEIGHT}): its own price, so a live remainder is the sum of the steps still to
 * finish rather than a share of plan ticks, which are spread evenly over steps of very different
 * length. {@link Kind} places it in the module's shape: the compile prefix, the suite, or a
 * packaging tail that runs beside the suite.
 */
public record StepCost(String step, int weight, Kind kind) {

    /** Where the step sits in {@code prefix + max(suite, tail)}. */
    public enum Kind {
        PRE,
        TEST,
        TAIL
    }

    public StepCost {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(kind, "kind");
        weight = Math.max(0, weight);
    }
}
