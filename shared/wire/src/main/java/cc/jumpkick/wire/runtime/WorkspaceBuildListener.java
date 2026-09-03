// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.run.BuildPlanListener;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workspace-build events for front-ends. Defaults are no-ops; {@link #onModuleStart} attaches a
 * per-module {@link BuildPlanListener}; {@link #onPlan} supplies weights for aggregate progress.
 */
public interface WorkspaceBuildListener {

    /** A no-op listener (a headless build that only wants the returned result). */
    WorkspaceBuildListener NOOP = new WorkspaceBuildListener() {};

    /**
     * Pre-execution workspace progress (lock freshen, graph resolve, prepare modules, …). {@code
     * stage} is a stable wire key ({@code checking}, {@code lock}, {@code graph}, {@code plan});
     * {@code done}/{@code total} are stage-local counters ({@code total == 0} means indeterminate);
     * {@code label} is optional human text for the header. Emitted before {@link #onPlan}.
     */
    default void onPreflight(String stage, int done, int total, String label) {}

    /** The resolved modules in dependency order, each with its assembled plan + estimated weight. */
    default void onPlan(List<ModulePlan> plan) {}

    /**
     * The module dependency graph: each module dir → the dirs that must build before it. Emitted once
     * up front (alongside {@link #onPlan}) so a caller can reconstruct the module DAG — used by the
     * engine's critical-path cache-benefit metric to compose per-module cold-cost estimates.
     */
    default void onModuleGraph(Map<Path, Set<Path>> prereqs) {}

    /**
     * A module is about to build. Return the {@link BuildPlanListener} to attach to its plan (its
     * step/progress/output events), or {@code null} / a no-op listener to ignore them.
     */
    default BuildPlanListener onModuleStart(ModulePlan module) {
        return new BuildPlanListener() {};
    }

    /** A module finished (success or failure). */
    default void onModuleFinish(ModuleOutcome outcome) {}

    /**
     * Remaining wall-work {@code R(t)} in milliseconds. Seeded once with {@code R0} at plan time
     * (same value as {@code jk explain}), then re-emitted as modules progress/finish so the
     * countdown tracks residual schedule — not open-loop {@code seed − elapsed}. {@code 0} means
     * no remaining work (or no trustworthy model — clients count up).
     */
    default void onEtaEstimate(long remainingMs) {}

    /**
     * Full remaining-work model for this build: seed {@code R0}, schedule parameters, and per-module
     * costs. Emitted once before execute so front-ends / engine trackers can drive residual
     * {@code R(t)} and the progress bar from the same oracle.
     */
    default void onWorkModel(WorkModel model) {}

    /**
     * Workspace aggregate progress from the engine tracker. Clients must paint this for
     * the bar / {@code progress} rider — do not re-aggregate from per-module plan ticks.
     * {@link WorkspaceProgressTracker.Snapshot#remainingMs()} / {@code R0ms()} mirror the ETA.
     */
    default void onWorkspaceProgress(WorkspaceProgressTracker.Snapshot snapshot) {}

    /** The whole workspace build finished. */
    default void onWorkspaceFinish(WorkspaceResult result) {}
}
