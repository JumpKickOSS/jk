// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Plan-time remaining-work model: seed wall ms {@link #R0()}, schedule knobs, and per-module costs
 * in declaration order (topo order). Used to construct {@link RemainingWork}.
 */
public record WorkModel(long R0, int concurrency, boolean serial, boolean parallelTests, List<ModuleWorkCost> costs) {

    public WorkModel {
        costs = costs == null ? List.of() : List.copyOf(costs);
        R0 = Math.max(0, R0);
        concurrency = Math.max(0, concurrency);
    }

    public RemainingWork toRemainingWork() {
        return RemainingWork.seed(costs, R0, concurrency, serial, parallelTests);
    }

    public static WorkModel of(
            long R0, int concurrency, boolean serial, boolean parallelTests, List<ModuleWorkCost> costs) {
        Objects.requireNonNull(costs, "costs");
        return new WorkModel(R0, concurrency, serial, parallelTests, costs);
    }
}
