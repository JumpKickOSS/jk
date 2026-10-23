// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The forecast of a workspace through a goal's phase: {@link TaskForecaster}'s walk over the plan
 * {@link ModulePipeline} builds for each module, every step priced by its own declaration. The one
 * entry the preflight, {@code jk explain} and the estimates take.
 */
public final class ForecastWalk {

    private ForecastWalk() {}

    /** Every module through PACKAGE, tests included. */
    public static List<TaskForecast.Module> of(BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache) {
        return of(graph, cas, actionCache, cache, false);
    }

    /** As above, without the test steps under {@code skipTests}. */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache, boolean skipTests) {
        return of(graph, cas, actionCache, cache, skipTests, WorkspaceTarget.PACKAGE);
    }

    /** As above through {@code target}'s phase: COMPILE, TEST or PACKAGE. */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target) {
        return of(graph, cas, actionCache, cache, skipTests, target, null, null);
    }

    /**
     * As above under {@code profile}, walking only {@code only} when it is not null (every other
     * module reported with no work).
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            @Nullable String profile,
            @Nullable Set<Path> only) {
        if (graph.topoOrder().isEmpty()) return List.of();
        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) moduleDirs.add(u.dir());
        Path entry = graph.topoOrder().getLast().dir();
        WorkspaceRequest request = new WorkspaceRequest(
                        entry, cache, null, 0, profile, skipTests, false, 0, null, false, false)
                .withSpec(WorkspaceSpec.of(target));
        Set<Path> jarConsumed = WorkspacePreflightPhase.jarConsumed(graph);
        Set<Path> launched = WorkspacePreflightPhase.launchedAssemblies(graph);
        return TaskForecaster.of(
                graph, cas, actionCache, cache, skipTests, target, profile, only, unit -> ModulePipeline.planned(
                                unit, request, moduleDirs, false, jarConsumed, launched, SiblingArtifacts.NONE)
                        .plan());
    }
}
