// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plan-time remaining-work model: seed wall ms {@link #R0()}, schedule knobs, and per-module costs
 * in declaration order (topo order). Constructs {@link RemainingWork}.
 *
 * <p>{@link #steps()} holds, per module, the steps it is priced to run with their own prices. A
 * model that carries them is priced in ms at {@link #MS_PER_WEIGHT} with no global scale, and its
 * live remainder counts each module down step by step; a module without them falls back to its
 * plan-tick fraction.
 */
public record WorkModel(
        long R0,
        int concurrency,
        boolean serial,
        boolean parallelTests,
        List<ModuleWorkCost> costs,
        Map<Path, List<StepCost>> steps) {

    /** The fixed wall of one {@link WorkSchedule} weight unit, in ms. */
    public static final int MS_PER_WEIGHT = 150;

    public WorkModel {
        costs = costs == null ? List.of() : List.copyOf(costs);
        steps = steps == null ? Map.of() : Map.copyOf(steps);
        R0 = Math.max(0, R0);
        concurrency = Math.max(0, concurrency);
    }

    public WorkModel(long R0, int concurrency, boolean serial, boolean parallelTests, List<ModuleWorkCost> costs) {
        this(R0, concurrency, serial, parallelTests, costs, Map.of());
    }

    public RemainingWork toRemainingWork() {
        return steps.isEmpty()
                ? RemainingWork.seed(costs, R0, concurrency, serial, parallelTests)
                : RemainingWork.seedSteps(costs, steps, concurrency, serial, parallelTests);
    }

    public static WorkModel of(
            long R0, int concurrency, boolean serial, boolean parallelTests, List<ModuleWorkCost> costs) {
        Objects.requireNonNull(costs, "costs");
        return new WorkModel(R0, concurrency, serial, parallelTests, costs);
    }
}
