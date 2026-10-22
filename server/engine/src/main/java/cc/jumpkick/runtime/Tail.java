// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.BuildPlan;

/**
 * What a build-like goal does after PACKAGE: install's {@code cache-install}, native's
 * {@code native-image}, image's {@code image-plan} and {@code write-image}, train's training run,
 * publish's upload. A tail sees a plan that already runs every phase through PACKAGE and may only
 * {@link BuildPlan.Builder#extend extend} it, so it cannot drop a phase the goal shares with
 * {@code jk build}. Planned only by {@code ModulePipeline}.
 */
@FunctionalInterface
public interface Tail {

    /** No steps past PACKAGE: {@code jk build}, {@code jk run}, and every module a goal does not select. */
    Tail NONE = (plan, in) -> {};

    /** Append this goal's steps; each one through {@link BuildPlan.Builder#extend}. */
    void append(BuildPlan.Builder plan, BuildPlanner.Inputs in);

    /**
     * True when this tail builds the module's native image itself (with its own main and arguments),
     * so the PACKAGE phase must not add the one {@code native = "always"} declares.
     */
    default boolean buildsNativeImage() {
        return false;
    }
}
