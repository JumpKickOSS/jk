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
        Map<Path, List<StepCost>> steps,
        /**
         * {@code 0} for the build's seed; {@code n} for its n-th re-pricing, once a module the
         * estimate guessed at compiled and settled what its dependents run (JK-0PDBN). A revision
         * replaces the residual and keeps the clock the seed anchored.
         */
        int revision) {

    /** The fixed wall of one {@link WorkSchedule} weight unit, in ms. */
    public static final int MS_PER_WEIGHT = 150;

    public WorkModel {
        costs = costs == null ? List.of() : List.copyOf(costs);
        steps = steps == null ? Map.of() : Map.copyOf(steps);
        R0 = Math.max(0, R0);
        concurrency = Math.max(0, concurrency);
        revision = Math.max(0, revision);
    }

    /** A build's seed model. */
    public WorkModel(
            long R0,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            List<ModuleWorkCost> costs,
            Map<Path, List<StepCost>> steps) {
        this(R0, concurrency, serial, parallelTests, costs, steps, 0);
    }

    public WorkModel(long R0, int concurrency, boolean serial, boolean parallelTests, List<ModuleWorkCost> costs) {
        this(R0, concurrency, serial, parallelTests, costs, Map.of());
    }

    /** This model as the build's {@code n}-th re-pricing. */
    public WorkModel asRevision(int n) {
        return new WorkModel(R0, concurrency, serial, parallelTests, costs, steps, n);
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
