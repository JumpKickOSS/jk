// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.PipelineListener;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workspace-build events for front-ends. Defaults are no-ops; {@link #onModuleStart} attaches a
 * per-module {@link PipelineListener}; {@link #onPlan} supplies weights for aggregate progress.
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

    /** The resolved modules in dependency order, each with its assembled pipeline + estimated weight. */
    default void onPlan(List<ModulePlan> plan) {}

    /**
     * The module dependency graph: each module dir → the dirs that must build before it. Emitted once
     * up front (alongside {@link #onPlan}) so a caller can reconstruct the module DAG — used by the
     * engine's critical-path cache-benefit metric to compose per-module cold-cost estimates.
     */
    default void onModuleGraph(Map<Path, Set<Path>> prereqs) {}

    /**
     * A module is about to build. Return the {@link PipelineListener} to attach to its pipeline (its
     * step/progress/output events), or {@code null} / a no-op listener to ignore them.
     */
    default PipelineListener onModuleStart(ModulePlan module) {
        return new PipelineListener() {};
    }

    /** A module finished (success or failure). */
    default void onModuleFinish(ModuleOutcome outcome) {}

    /**
     * A wall-clock estimate (ms) for the whole build, computed by the engine's schedule-aware model
     * emitted once up front (from learned/calibrated rates) and re-projected as modules finish and
     * real throughput is measured. A front-end renders it as a countdown; {@code 0} means "no
     * trustworthy estimate — count up instead".
     */
    default void onEtaEstimate(long millis) {}

    /**
     * Workspace aggregate progress from the engine tracker. Clients must paint this for
     * the bar / {@code progress} rider — do not re-aggregate from per-module pipeline ticks.
     */
    default void onWorkspaceProgress(WorkspaceProgressTracker.Snapshot snapshot) {}

    /** The whole workspace build finished. */
    default void onWorkspaceFinish(WorkspaceResult result) {}
}
