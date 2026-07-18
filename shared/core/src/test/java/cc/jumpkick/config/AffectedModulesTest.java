// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AffectedModulesTest {

    @Test
    void maps_paths_under_libs_a_and_closes_reverse_deps() {
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Path a = root.resolve("libs/a");
        Path b = root.resolve("libs/b");
        Path app = root.resolve("app");

        // edges: module → prereqs (app depends on a; b is independent)
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        edges.put(a, Set.of());
        edges.put(b, Set.of());
        edges.put(app, Set.of(a));

        Set<Path> affected = AffectedModules.fromChangedPaths(
                root, List.of(a, b, app), edges, List.of("libs/a/src/Main.java"));

        assertThat(affected).containsExactlyInAnyOrder(a, app);
        assertThat(affected).doesNotContain(b);
    }

    @Test
    void workspace_level_change_marks_all_modules() {
        Path root = Path.of("/ws").toAbsolutePath().normalize();
        Path a = root.resolve("libs/a");
        Path b = root.resolve("libs/b");
        Map<Path, Set<Path>> edges = Map.of(a, Set.of(), b, Set.of());

        Set<Path> affected =
                AffectedModules.fromChangedPaths(root, List.of(a, b), edges, List.of("jk.toml"));

        assertThat(affected).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void edgesFor_uses_workspace_sibling_deps() {
        // Minimal synthetic projects: app depends on lib by coordinate.
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
        Path libDir = Path.of("/ws/lib").toAbsolutePath().normalize();
        Path appDir = Path.of("/ws/app").toAbsolutePath().normalize();
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(libDir, lib);
        modules.put(appDir, app);

        Map<Path, Set<Path>> edges = AffectedModules.edgesFor(modules);
        assertThat(edges.get(appDir)).contains(libDir);
        assertThat(edges.get(libDir)).isEmpty();

        Set<Path> affected = AffectedModules.fromChangedPaths(
                Path.of("/ws"), modules.keySet(), edges, List.of("lib/src/X.java"));
        assertThat(affected).containsExactlyInAnyOrder(libDir, appDir);
    }
}
