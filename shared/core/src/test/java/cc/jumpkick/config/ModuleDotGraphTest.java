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
        JkBuild lib = JkBuildParser.parse(
                """
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse(
                """
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
        JkBuild lib = JkBuildParser.parse(
                """
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        JkBuild app = JkBuildParser.parse(
                """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);
        JkBuild other = JkBuildParser.parse(
                """
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
        assertThat(dot).isEqualTo(
                """
                digraph modules {
                  rankdir=LR;
                  node [shape=box, fontname="Helvetica"];
                }
                """);
    }

    @Test
    void single_module_dot_one_node() {
        JkBuild b = JkBuildParser.parse(
                """
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
}
