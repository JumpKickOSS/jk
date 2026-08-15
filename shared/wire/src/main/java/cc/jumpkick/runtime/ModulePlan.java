// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildPlan;
import java.nio.file.Path;

/**
 * One module of the {@link WorkspaceBuildListener#onPlan} burst: identity, plan outline, and
 * sizing. On the wire path {@link #plan()} is inert (name/steps only).
 */
public record ModulePlan(Path dir, String coord, BuildPlan plan, int weight, boolean fullyCached, Path cache) {

    /** Reconstruct a plan client-side from wire-level data. */
    public static ModulePlan fromWire(
            Path dir, String coord, BuildPlan plan, int weight, boolean fullyCached, Path cache) {
        return new ModulePlan(dir, coord, plan, weight, fullyCached, cache);
    }
}
