// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk explain -m X} prices the cone {@code jk build -m X} schedules — X and its
 * prerequisites — and nothing beside it. The cone's modules keep the verdicts the whole-graph
 * forecast gives them, so the totals are the ones the build's own countdown seed is computed from.
 */
class ExplainSelectionConeTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_selected_leaf_is_priced_with_its_prerequisites_only(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).as(graph.errors().toString()).isFalse();
        Path a = BuildGraph.canonicalPath(ws.resolve("a"));
        Path b = BuildGraph.canonicalPath(ws.resolve("b"));
        Path c = BuildGraph.canonicalPath(ws.resolve("c"));

        ExplainPlan whole = BuildForecasting.explainFromGraph(graph, cache, false, null);
        assertThat(whole.modules())
                .extracting(m -> BuildGraph.canonicalPath(m.dir()))
                .contains(a, b, c);

        ExplainPlan cone = BuildForecasting.explainSelection(graph, cache, false, Set.of(c));

        assertThat(cone.modules())
                .extracting(m -> BuildGraph.canonicalPath(m.dir()))
                .as("the leaf and its prerequisite; the unrelated module is not in the plan")
                .containsExactlyInAnyOrder(b, c);
        assertThat(cone.edges().keySet()).extracting(BuildGraph::canonicalPath).containsExactlyInAnyOrder(b, c);
        assertThat(cone.edges().entrySet().stream()
                        .filter(e -> BuildGraph.canonicalPath(e.getKey()).equals(c))
                        .findFirst()
                        .orElseThrow()
                        .getValue())
                .extracting(BuildGraph::canonicalPath)
                .containsExactly(b);

        // The cone's modules carry exactly the verdicts the whole-graph forecast gave them: the
        // same steps, so the same ETA the build's countdown seeds from.
        Map<Path, List<TaskForecast.Task>> wholeSteps = steps(whole);
        for (TaskForecast.Module m : cone.modules()) {
            assertThat(m.steps()).isEqualTo(wholeSteps.get(BuildGraph.canonicalPath(m.dir())));
        }
        assertThat(cone.maxReadyWidth()).isEqualTo(whole.maxReadyWidth());
        assertThat(BuildService.estimateEtaMillis(cone, ws, cache, 0, null, null, false, false, true, 8))
                .isEqualTo(BuildService.estimateEtaMillis(
                        BuildService.restrictToSelection(whole, Set.of(b, c)),
                        ws,
                        cache,
                        0,
                        null,
                        null,
                        false,
                        false,
                        true,
                        8));
    }

    @Test
    void an_empty_selection_is_the_whole_workspace(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        var root = JkBuildParser.parse(ws.resolve("jk.toml"));
        ExplainPlan plan = BuildService.explain(ws, root, cache, false, Set.of());
        assertThat(plan.modules())
                .extracting(m -> m.dir().getFileName().toString())
                .contains("a", "b", "c");
    }

    private static Map<Path, List<TaskForecast.Task>> steps(ExplainPlan plan) {
        Map<Path, List<TaskForecast.Task>> out = new HashMap<>();
        for (TaskForecast.Module m : plan.modules()) out.put(BuildGraph.canonicalPath(m.dir()), m.steps());
        return out;
    }

    /** a, b and c; c depends on b; a stands alone. */
    private static Path workspace(Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "t"
                name    = "ws"
                version = "1.0"
                java    = 25

                [workspace]
                modules = ["a", "b", "c"]
                """);
        lock(ws);
        member(ws, "a", "");
        member(ws, "b", "");
        member(ws, "c", """

                [dependencies]
                b = { workspace = true }
                """);
        return ws;
    }

    private static void member(Path ws, String name, String tail) throws Exception {
        Path dir = Files.createDirectories(ws.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "t"
                name    = "%s"
                version = "1.0"
                java    = 25
                """.formatted(name) + tail);
        Path src = Files.createDirectories(dir.resolve("src/main/java/t"));
        Files.writeString(
                src.resolve(Character.toUpperCase(name.charAt(0)) + ".java"),
                "package t;\nclass " + Character.toUpperCase(name.charAt(0)) + " {}\n");
        lock(dir);
    }

    private static void lock(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
    }
}
