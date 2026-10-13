// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PreflightMemoFixtures.writeProject;
import static cc.jumpkick.runtime.PreflightMemoFixtures.writeWorkspace;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The graph rebuild and plan shape memos. */
class PreflightGraphMemoTest {

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
                .containsExactlyElementsOf(full.topoOrder().stream()
                        .map(BuildGraph.BuildUnit::coord)
                        .toList());
        // Edges: same prereq counts
        assertThat(loaded.edges().keySet()).hasSize(full.edges().keySet().size());
    }

    @Test
    void tryLoadGraph_misses_when_module_toml_changes(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);

        Files.writeString(tmp.resolve("a/jk.toml"), """
                group = "t"
                name = "a"
                version = "0.2.0"
                jdk = 25
                java = 25
                """);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void tryLoadGraph_misses_when_root_gains_sources(@TempDir Path tmp) throws Exception {
        // Root buildability depends on it having sources, not on any toml — the structure key
        // must see the transition or the root module is silently never built.
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isPresent();

        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Root.java"), "class Root {}\n");
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void tryLoadGraph_misses_when_workspace_module_list_changes(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);
        // Drop b from the workspace list (folder still exists) — entry toml changes structure key.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

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
                .isEqualTo(first.topoOrder().stream()
                        .map(BuildGraph.BuildUnit::coord)
                        .toList());
    }

    @Test
    void shape_memo_round_trip(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        var shape = new PreflightMemo.BuildPlanShape(
                42,
                8,
                List.of(
                        new PreflightMemo.BuildPlanShape.StepShape("compile-java", "compile"),
                        new PreflightMemo.BuildPlanShape.StepShape("package-jar", "package")));
        PreflightMemo.storeShape(tmp, mod, false, shape);
        Optional<PreflightMemo.BuildPlanShape> hit = PreflightMemo.tryLoadShape(tmp, mod, false);
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
        PreflightMemo.storeShape(tmp, mod, false, new PreflightMemo.BuildPlanShape(10, 0, List.of()));
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "9.9.9"
                jdk = 25
                java = 25
                """);
        assertThat(PreflightMemo.tryLoadShape(tmp, mod, false)).isEmpty();
    }

    @Test
    void shape_memo_skip_tests_variants_coexist(@TempDir Path tmp) throws Exception {
        // fingerprint embeds skipTests — both rows must persist.
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        PreflightMemo.storeShape(
                tmp,
                mod,
                false,
                new PreflightMemo.BuildPlanShape(
                        40, 10, List.of(new PreflightMemo.BuildPlanShape.StepShape("run-tests", "test"))));
        PreflightMemo.storeShape(
                tmp,
                mod,
                true,
                new PreflightMemo.BuildPlanShape(
                        30, 0, List.of(new PreflightMemo.BuildPlanShape.StepShape("compile-java", "compile"))));
        Optional<PreflightMemo.BuildPlanShape> withTests = PreflightMemo.tryLoadShape(tmp, mod, false);
        Optional<PreflightMemo.BuildPlanShape> skipTests = PreflightMemo.tryLoadShape(tmp, mod, true);
        assertThat(withTests).isPresent();
        assertThat(withTests.get().weight()).isEqualTo(40);
        assertThat(withTests.get().testWeight()).isEqualTo(10);
        assertThat(skipTests).isPresent();
        assertThat(skipTests.get().weight()).isEqualTo(30);
        assertThat(skipTests.get().testWeight()).isEqualTo(0);
    }

    @Test
    void shape_memo_concurrent_upserts_retain_all_modules(@TempDir Path tmp) throws Exception {
        // parallel prepare must not drop peer rows.
        writeProject(tmp);
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("jk.toml"), """
                group = "t"
                name = "a"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Files.writeString(b.resolve("jk.toml"), """
                group = "t"
                name = "b"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        var shapeA = new PreflightMemo.BuildPlanShape(11, 0, List.of());
        var shapeB = new PreflightMemo.BuildPlanShape(22, 0, List.of());
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 40; i++) PreflightMemo.storeShape(tmp, a, false, shapeA);
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 40; i++) PreflightMemo.storeShape(tmp, b, false, shapeB);
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertThat(PreflightMemo.tryLoadShape(tmp, a, false)).isPresent();
        assertThat(PreflightMemo.tryLoadShape(tmp, a, false).orElseThrow().weight())
                .isEqualTo(11);
        assertThat(PreflightMemo.tryLoadShape(tmp, b, false)).isPresent();
        assertThat(PreflightMemo.tryLoadShape(tmp, b, false).orElseThrow().weight())
                .isEqualTo(22);
    }

    @Test
    void costOf_from_shape_weights_matches_schedule_inputs(@TempDir Path tmp) {
        // ETA path builds ModuleCost without assembling a plan.
        var cost = EffortWeights.costOf(tmp, Set.of(), 100, 15);
        assertThat(cost.weight()).isEqualTo(100);
        assertThat(cost.testWeight()).isEqualTo(15);
        assertThat(cost.dir()).isEqualTo(tmp);
    }

    @Test
    void tryLoadGraph_misses_when_logic_or_guards_appear_or_leave(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isPresent();

        Files.createDirectories(tmp.resolve("jk"));
        Files.writeString(tmp.resolve("jk/after-build.kts"), "println(1)\n");
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();

        PreflightMemo.storeGraph(tmp, BuildGraph.resolve(tmp, entry));
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isPresent();
        Files.writeString(tmp.resolve("jk-guards.toml"), "# rules\n");
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();

        PreflightMemo.storeGraph(tmp, BuildGraph.resolve(tmp, entry));
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isPresent();
        Files.delete(tmp.resolve("jk-guards.toml"));
        Files.delete(tmp.resolve("jk/after-build.kts"));
        Files.delete(tmp.resolve("jk"));
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }
}
