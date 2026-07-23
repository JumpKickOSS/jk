// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1100/1108/1109/1112/1113: local dirty-set, graph rebuild, pipeline shape memos. */
class PreflightMemoTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void store_then_load_hits_when_inputs_unchanged(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Set<Path> dirty = Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize());
        PreflightMemo.storeDirty(tmp, graph, false, dirty);

        Optional<Set<Path>> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEqualTo(dirty);
    }

    @Test
    void source_content_change_misses_memo_even_if_size_unchanged(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path src = tmp.resolve("src/main/java/App.java");
        Files.writeString(src, "class App { int x = 1; }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        PreflightMemo.storeDirty(tmp, graph, false, Set.of());

        Files.writeString(src, "class App { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void forecastDirtyDirs_uses_memo_on_second_call(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Session session = Session.defaults().withConfig(JkConfig.empty());
        Set<Path> first = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(PreflightMemo.memoFile(tmp)).exists();
        Set<Path> second = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void successful_build_overwrites_memo_with_all_clean(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        PreflightMemo.storeDirty(
                tmp, graph, false, Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize()));
        PreflightMemo.storeDirty(tmp, graph, false, Set.of());
        Optional<Set<Path>> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEmpty();
    }

    @Test
    void graph_structure_memo_matches_after_store(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        assertThat(PreflightMemo.graphStructureMatches(tmp, graph)).isFalse();
        PreflightMemo.storeGraph(tmp, graph);
        assertThat(PreflightMemo.graphStructureMatches(tmp, graph)).isTrue();
        assertThat(PreflightMemo.graphMemoFile(tmp)).exists();
    }

    @Test
    void tryLoadGraph_rebuilds_without_workspace_loader(@TempDir Path tmp) throws Exception {
        // Multi-module workspace so membership walk would otherwise load modules/
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        assertThat(full.hasErrors()).isFalse();
        assertThat(full.topoOrder()).hasSize(2);
        PreflightMemo.storeGraph(tmp, full);

        Optional<BuildGraph.Result> hit = PreflightMemo.tryLoadGraph(tmp);
        assertThat(hit).isPresent();
        BuildGraph.Result loaded = hit.get();
        assertThat(loaded.hasErrors()).isFalse();
        assertThat(loaded.topoOrder()).hasSize(2);
        assertThat(loaded.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList())
                .containsExactlyElementsOf(
                        full.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList());
        // Edges: same prereq counts
        assertThat(loaded.edges().keySet()).hasSize(full.edges().keySet().size());
    }

    @Test
    void tryLoadGraph_misses_when_module_toml_changes(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);

        Files.writeString(
                tmp.resolve("a/jk.toml"),
                """
                [project]
                group = "t"
                name = "a"
                version = "0.2.0"
                jdk = 21
                java = 21
                """);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void tryLoadGraph_misses_when_workspace_module_list_changes(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);
        // Drop b from the workspace list (folder still exists) — entry toml changes structure key.
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["a"]
                """);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void resolve_uses_graph_memo_on_second_call(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result first = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, first);
        // Second resolve should hit memo path (same structure)
        BuildGraph.Result second = BuildGraph.resolve(tmp, entry);
        assertThat(second.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList())
                .isEqualTo(first.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList());
    }

    @Test
    void shape_memo_round_trip(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        var shape = new PreflightMemo.PipelineShape(
                42,
                8,
                java.util.List.of(
                        new PreflightMemo.PipelineShape.StepShape("compile-java", "compile"),
                        new PreflightMemo.PipelineShape.StepShape("package-jar", "package")));
        PreflightMemo.storeShape(tmp, mod, false, shape);
        Optional<PreflightMemo.PipelineShape> hit = PreflightMemo.tryLoadShape(tmp, mod, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().weight()).isEqualTo(42);
        assertThat(hit.get().testWeight()).isEqualTo(8);
        assertThat(hit.get().steps()).hasSize(2);
        assertThat(hit.get().steps().getFirst().name()).isEqualTo("compile-java");
    }

    @Test
    void shape_memo_misses_when_toml_changes(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        PreflightMemo.storeShape(
                tmp, mod, false, new PreflightMemo.PipelineShape(10, 0, java.util.List.of()));
        Files.writeString(
                tmp.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "9.9.9"
                jdk = 21
                java = 21
                """);
        assertThat(PreflightMemo.tryLoadShape(tmp, mod, false)).isEmpty();
    }

    @Test
    void costOf_from_shape_weights_matches_schedule_inputs(@TempDir Path tmp) {
        // JK-1114: ETA path builds ModuleCost without assembling a pipeline.
        var cost = EffortWeights.costOf(tmp, java.util.Set.of(), 100, 15);
        assertThat(cost.weight()).isEqualTo(100);
        assertThat(cost.testWeight()).isEqualTo(15);
        assertThat(cost.dir()).isEqualTo(tmp);
    }

    private static void writeProject(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 21
                java = 21
                """);
        Path src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.writeString(
                dir.resolve("jk.lock"),
                """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
    }

    private static void writeWorkspace(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : new String[] {"a", "b"}) {
            Path md = dir.resolve(m);
            Files.createDirectories(md.resolve("src/main/java"));
            Files.writeString(
                    md.resolve("jk.toml"),
                    """
                    [project]
                    group = "t"
                    name = "%s"
                    version = "0.1.0"
                    jdk = 21
                    java = 21
                    """
                            .formatted(m));
            Files.writeString(md.resolve("src/main/java/M.java"), "class M {}\n");
            Files.writeString(
                    md.resolve("jk.lock"),
                    """
                    version = 1
                    generated-by = "test"
                    resolution-algorithm = "pubgrub-v1"
                    """);
        }
    }
}
