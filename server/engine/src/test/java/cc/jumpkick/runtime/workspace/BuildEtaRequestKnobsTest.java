// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.SettledHostCalibration;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A forced build's estimate prices each module from the shape of the plan the build will run, and
 * that plan is the one the request's goal and knobs make: the estimate prices what {@link
 * ModulePipeline} plans, not a default build of the same module.
 */
@ExtendWith(SettledHostCalibration.class)
class BuildEtaRequestKnobsTest {

    @TempDir
    Path tmp;

    @Test
    void a_forced_compile_prices_no_suite_where_a_forced_build_does() throws Exception {
        BuildGraph.Result graph = project(tmp);
        Path cache = tmp.resolve("cache");
        Session forced = Session.defaults()
                .withConfig(JkConfig.empty().withRebuild(true))
                .withCacheDir(cache);

        EffortWeights.ModuleCost built =
                SessionContext.where(forced, () -> cost(graph, request(tmp, cache, WorkspaceTarget.PACKAGE)));
        EffortWeights.ModuleCost compiled =
                SessionContext.where(forced, () -> cost(graph, request(tmp, cache, WorkspaceTarget.COMPILE)));

        assertThat(built.testWeight()).as("a forced build runs the suite").isPositive();
        assertThat(compiled.testWeight())
                .as("a forced `jk compile` runs no suite, so its estimate prices none")
                .isZero();
        assertThat(compiled.weight() + compiled.tailWeight()).isLessThan(built.weight() + built.tailWeight());
    }

    private static WorkspaceRequest request(Path entry, Path cache, WorkspaceTarget target) {
        return new WorkspaceRequest(entry, cache, null, 1, null, false, false, 1, null, false, false)
                .withSpec(WorkspaceSpec.of(target));
    }

    /** The one module's cost, priced the way a forced build prices it: no forecast walk, so from its plan shape. */
    private static EffortWeights.ModuleCost cost(BuildGraph.Result graph, WorkspaceRequest request) {
        BuildGraph.BuildUnit unit = graph.topoOrder().getFirst();
        Path module = unit.dir();
        TaskForecast.Module shapeOnly = new TaskForecast.Module(module, "t:app", List.of(), 2, 30, true, false);
        BuildForecasting.Preflight forecast =
                new BuildForecasting.Preflight(Set.of(module), Map.of(), List.of(shapeOnly));
        WorkspacePreflightPhase.Context preflight = new WorkspacePreflightPhase.Context(
                request,
                unit.manifest(),
                graph,
                graph.topoOrder(),
                Set.of(module),
                Set.of(),
                Set.of(),
                Set.of(module),
                Optional.of(forecast));
        List<EffortWeights.ModuleCost> costs = WorkspaceResourcePhase.estimate(
                        preflight, request, request, graph.topoOrder(), true, true)
                .costs();
        assertThat(costs).hasSize(1);
        return costs.getFirst();
    }

    private static BuildGraph.Result project(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path main = Files.createDirectories(dir.resolve("src/main/java/t"));
        Files.writeString(main.resolve("App.java"), "package t; public class App {}\n");
        Path test = Files.createDirectories(dir.resolve("src/test/java/t"));
        Files.writeString(test.resolve("AppTest.java"), """
                package t;
                class AppTest {
                    @org.junit.jupiter.api.Test
                    void runs() {}
                }
                """);
        Files.createDirectories(BuildLayout.moduleTargetDir(dir));
        return BuildGraph.resolve(dir, JkBuildParser.parse(Files.readString(dir.resolve("jk.toml"))));
    }
}
