// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.Objects;

/**
 * One step a module is priced to run, in {@link WorkSchedule} weight units (ms over {@link
 * WorkModel#MS_PER_WEIGHT}): its own price, so a live remainder is the sum of the steps still to
 * finish rather than a share of plan ticks, which are spread evenly over steps of very different
 * length. {@link Kind} places it in the module's shape: the compile prefix, the suite, or a
 * packaging tail that runs beside the suite. {@code low} and {@code high} bound the price the way
 * the step's own recorded walls spread (the estimate's likely range, JK-4TEZ7); a step with no
 * spread has both at its price.
 */
public record StepCost(String step, int weight, Kind kind, int low, int high) {

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
        low = Math.max(0, Math.min(low, weight));
        high = Math.max(weight, high);
    }

    /** A step priced with no spread: its range is its price. */
    public StepCost(String step, int weight, Kind kind) {
        this(step, weight, kind, weight, weight);
    }

    /** The bound {@code which} picks: the price, the low or the high end of its range. */
    public int at(Bound which) {
        return switch (which) {
            case PRICE -> weight;
            case LOW -> low;
            case HIGH -> high;
        };
    }

    /** Which of a step's three figures a schedule is priced at. */
    public enum Bound {
        PRICE,
        LOW,
        HIGH
    }
}
