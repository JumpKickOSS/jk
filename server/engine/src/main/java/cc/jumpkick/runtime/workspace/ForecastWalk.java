// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.EffectiveManifest;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.BuildKnobs;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
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

    /** Every module through PACKAGE, tests included, under the ambient session's knobs. */
    public static List<TaskForecast.Module> of(BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache) {
        return of(graph, cas, actionCache, cache, false);
    }

    /** As above, without the test steps under {@code skipTests}. */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache, boolean skipTests) {
        return of(
                graph,
                cas,
                actionCache,
                cache,
                BuildKnobs.of(SessionContext.current()).withSkipTests(skipTests),
                null);
    }

    /**
     * Every module's plan under {@code knobs}, the build's own: its goal through that goal's phase
     * (COMPILE, TEST or PACKAGE), its test policy, profile, variant and client env. Walks only
     * {@code only} when it is not null, every other module reported with no work.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            BuildKnobs knobs,
            @Nullable Set<Path> only) {
        if (graph.topoOrder().isEmpty()) return List.of();
        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) moduleDirs.add(u.dir());
        Path entry = graph.topoOrder().getLast().dir();
        WorkspaceRequest request = knobs.request(entry, cache);
        Set<Path> jarConsumed = WorkspacePreflightPhase.jarConsumed(graph);
        Set<Path> launched = WorkspacePreflightPhase.launchedAssemblies(graph);
        EffectiveManifest.Selection manifest =
                EffectiveManifest.Selection.of(ModulePipeline.inputs(entry, request, moduleDirs, false));
        TaskForecaster.Planner planner = new TaskForecaster.Planner() {
            @Override
            public BuildPlan plan(BuildGraph.BuildUnit unit) throws Exception {
                return ModulePipeline.planned(
                                unit, request, moduleDirs, false, jarConsumed, launched, SiblingArtifacts.NONE)
                        .plan();
            }

            @Override
            public EffectiveManifest.Selection manifest() {
                return manifest;
            }
        };
        return TaskForecaster.of(graph, cas, actionCache, cache, knobs, only, planner);
    }
}
