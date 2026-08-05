// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleDotGraphTest {

    @Test
    void multi_module_emits_expected_edge() {
        JkBuild lib = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Path libDir = root.resolve("lib");
        Path appDir = root.resolve("app");
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(libDir, lib);
        modules.put(appDir, app);

        String dot = ModuleDotGraph.toDot(root, modules, null);

        assertThat(dot).startsWith("digraph modules {");
        assertThat(dot).contains("label=\"com.example:lib\"");
        assertThat(dot).contains("label=\"com.example:app\"");
        // app depends on lib → edge app → lib (prereq direction)
        assertThat(dot).containsPattern("m\\d+ -> m\\d+;");
        // Exactly one edge line
        long edges = dot.lines().filter(l -> l.contains("->")).count();
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void filter_drops_unselected_modules_and_edges() {
        JkBuild lib = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        JkBuild other = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "other"
                version = "1.0.0"
                """);
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Path libDir = root.resolve("lib");
        Path appDir = root.resolve("app");
        Path otherDir = root.resolve("other");
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(libDir, lib);
        modules.put(appDir, app);
        modules.put(otherDir, other);

        String dot = ModuleDotGraph.toDot(root, modules, Set.of(libDir));

        assertThat(dot).contains("com.example:lib");
        assertThat(dot).doesNotContain("com.example:app");
        assertThat(dot).doesNotContain("com.example:other");
        assertThat(dot.lines().filter(l -> l.contains("->")).count()).isZero();
    }

    @Test
    void empty_map_is_valid_trivial_graph() {
        String dot = ModuleDotGraph.toDot(Path.of("/ws"), Map.of(), null);
        assertThat(dot).isEqualTo("""
                digraph modules {
                  rankdir=LR;
                  node [shape=box, fontname="Helvetica"];
                }
                """);
    }

    @Test
    void single_module_dot_one_node() {
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        String dot = ModuleDotGraph.singleModuleDot(b, Path.of("/proj"));
        assertThat(dot).contains("label=\"g:n\"");
        assertThat(dot.lines().filter(l -> l.contains("->")).count()).isZero();
    }

    @Test
    void quote_escapes_specials() {
        assertThat(ModuleDotGraph.quote("a\"b")).isEqualTo("\"a\\\"b\"");
    }

    @Test
    void mermaid_multi_module_emits_edge() {
        JkBuild lib = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(root.resolve("lib"), lib);
        modules.put(root.resolve("app"), app);

        String mmd = ModuleDotGraph.toMermaid(root, modules, null);

        assertThat(mmd).startsWith("flowchart LR\n");
        assertThat(mmd).contains("[\"com.example:lib\"]");
        assertThat(mmd).contains("[\"com.example:app\"]");
        assertThat(mmd).containsPattern("m\\d+ --> m\\d+");
        long edges = mmd.lines().filter(l -> l.contains("-->")).count();
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void mermaid_filter_and_empty() {
        JkBuild lib = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Path libDir = root.resolve("lib");
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(libDir, lib);
        modules.put(root.resolve("app"), app);

        String filtered = ModuleDotGraph.render("mermaid", root, modules, Set.of(libDir));
        assertThat(filtered).contains("com.example:lib");
        assertThat(filtered).doesNotContain("com.example:app");
        assertThat(filtered.lines().filter(l -> l.contains("-->")).count()).isZero();

        assertThat(ModuleDotGraph.toMermaid(root, Map.of(), null)).isEqualTo("flowchart LR\n");
    }

    @Test
    void single_module_mermaid() {
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        String mmd = ModuleDotGraph.singleModuleMermaid(b, Path.of("/proj"));
        assertThat(mmd).isEqualTo("flowchart LR\n  m0[\"g:n\"]\n");
    }

    @Test
    void is_supported_format() {
        assertThat(ModuleDotGraph.isSupportedFormat("dot")).isTrue();
        assertThat(ModuleDotGraph.isSupportedFormat("Mermaid")).isTrue();
        assertThat(ModuleDotGraph.isSupportedFormat("plantuml")).isFalse();
    }

    @Test
    void graph_data_matches_mermaid_edges() {
        JkBuild lib = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(root.resolve("lib"), lib);
        modules.put(root.resolve("app"), app);

        ModuleDotGraph.GraphData data = ModuleDotGraph.graphData(root, modules, null);

        assertThat(data.workspace()).isTrue();
        assertThat(data.nodes()).hasSize(2);
        assertThat(data.nodes().stream().map(ModuleDotGraph.Node::label).toList())
                .containsExactlyInAnyOrder("com.example:lib", "com.example:app");
        assertThat(data.edges()).hasSize(1);
        // dependent (app) → prereq (lib)
        String appId = data.nodes().stream()
                .filter(n -> n.label().equals("com.example:app"))
                .findFirst()
                .orElseThrow()
                .id();
        String libId = data.nodes().stream()
                .filter(n -> n.label().equals("com.example:lib"))
                .findFirst()
                .orElseThrow()
                .id();
        assertThat(data.edges().getFirst()).isEqualTo(new ModuleDotGraph.Edge(appId, libId));
        assertThat(data.nodes().stream().map(ModuleDotGraph.Node::path).toList())
                .containsExactlyInAnyOrder("lib", "app");
    }

    @Test
    void single_module_data_one_node() {
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        ModuleDotGraph.GraphData data = ModuleDotGraph.singleModuleData(b, Path.of("/proj"));
        assertThat(data.workspace()).isFalse();
        assertThat(data.nodes()).containsExactly(new ModuleDotGraph.Node("m0", "g:n", "."));
        assertThat(data.edges()).isEmpty();
    }

    @Test
    void for_project_dir_workspace_and_missing(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        assertThat(ModuleDotGraph.forProjectDir(tmp.resolve("nope")).nodes()).isEmpty();

        Path ws = tmp.resolve("ws");
        java.nio.file.Files.createDirectories(ws.resolve("lib"));
        java.nio.file.Files.createDirectories(ws.resolve("app"));
        java.nio.file.Files.writeString(
                ws.resolve("jk.toml"),
                """
                [project]
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        java.nio.file.Files.writeString(
                ws.resolve("lib/jk.toml"),
                """
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        java.nio.file.Files.writeString(
                ws.resolve("app/jk.toml"),
                """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);

        ModuleDotGraph.GraphData data = ModuleDotGraph.forProjectDir(ws);
        assertThat(data.workspace()).isTrue();
        assertThat(data.nodes()).hasSize(2);
        assertThat(data.edges()).hasSize(1);
    }
}
