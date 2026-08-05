// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildPlan;
import java.nio.file.Path;

/**
 * One module entry of the {@link WorkspaceBuildListener#onPlan} burst: identity, pipeline outline, and
 * sizing for render/calibration. On the wire path {@link #pipeline()} is inert (name/steps only).
 */
public final class ModulePlan {
    private final Path dir;
    private final String coord;
    private final BuildPlan pipeline;
    private final int weight;
    private final boolean fullyCached;
    private final Path cache;

    public ModulePlan(Path dir, String coord, BuildPlan pipeline, int weight, boolean fullyCached, Path cache) {
        this.dir = dir;
        this.coord = coord;
        this.pipeline = pipeline;
        this.weight = weight;
        this.fullyCached = fullyCached;
        this.cache = cache;
    }

    /** Reconstruct a plan client-side from wire-level data (engine front-ends). */
    public static ModulePlan fromWire(
            Path dir, String coord, BuildPlan pipeline, int weight, boolean fullyCached, Path cache) {
        return new ModulePlan(dir, coord, pipeline, weight, fullyCached, cache);
    }

    public String coord() {
        return coord;
    }

    public Path dir() {
        return dir;
    }

    public BuildPlan pipeline() {
        return pipeline;
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
