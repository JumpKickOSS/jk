// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildPlan;
import java.nio.file.Path;

/**
 * One module entry of the {@link WorkspaceBuildListener#onPlan} burst: identity, plan outline, and
 * sizing for render/calibration. On the wire path {@link #plan()} is inert (name/steps only).
 */
public final class ModulePlan {
    private final Path dir;
    private final String coord;
    private final BuildPlan plan;
    private final int weight;
    private final boolean fullyCached;
    private final Path cache;

    public ModulePlan(Path dir, String coord, BuildPlan plan, int weight, boolean fullyCached, Path cache) {
        this.dir = dir;
        this.coord = coord;
        this.plan = plan;
        this.weight = weight;
        this.fullyCached = fullyCached;
        this.cache = cache;
    }

    /** Reconstruct a plan client-side from wire-level data (engine front-ends). */
    public static ModulePlan fromWire(
            Path dir, String coord, BuildPlan plan, int weight, boolean fullyCached, Path cache) {
        return new ModulePlan(dir, coord, plan, weight, fullyCached, cache);
    }

    public String coord() {
        return coord;
    }

    public Path dir() {
        return dir;
    }

    public BuildPlan plan() {
        return plan;
    }

    public int weight() {
        return weight;
    }

    public boolean fullyCached() {
        return fullyCached;
    }

    public Path cache() {
        return cache;
    }
}
