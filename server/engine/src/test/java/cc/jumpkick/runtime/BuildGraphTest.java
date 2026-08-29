// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Graph-resolution coverage for {@link BuildGraph}: workspace-module topo-sort by sibling
 * dependency order. There is no separate "composite dependency" unit anymore — a local sibling is
 * always a workspace module, and a git dependency (any ref type) is a lock-pinned coordinate, not
 * a build unit here.
 */
class BuildGraphTest {

    private static BuildGraph.Result resolve(Path entryDir) throws IOException {
        JkBuild entry = JkBuildParser.parse(Files.readString(entryDir.resolve("jk.toml")));
        return BuildGraph.resolve(entryDir, entry);
    }

    private static List<String> coords(BuildGraph.Result r) {
        return r.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList();
    }

    @Test
    void standalone_project_is_a_single_root_unit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactly("com.example:app");
    }

    @Test
    void workspace_modules_become_units_in_dependency_order(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(tmp.resolve("core"));
        Files.writeString(tmp.resolve("core/jk.toml"), """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        // app depends on core (sibling), so core builds first.
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                core = { group = "com.example", name = "core", version = "1.0.0" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        List<String> order = coords(r);
        assertThat(order).containsExactlyInAnyOrder("com.example:core", "com.example:app");
        assertThat(order.indexOf("com.example:core")).isLessThan(order.indexOf("com.example:app"));
    }

    @Test
    void test_only_kind_tests_edge_orders_sibling_before_consumer(@TempDir Path tmp) throws Exception {
        // Mill testModuleDeps: app has no main dep on lib, only kind=tests under test-deps.
        // ModuleOrder must still schedule lib before app so lib's test classes exist.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(tmp.resolve("lib"));
        Files.writeString(tmp.resolve("lib/jk.toml"), """
                group = "com.example"
                name  = "lib"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25

                [test-dependencies]
                lib = { workspace = true, kind = "tests" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        List<String> order = coords(r);
        assertThat(order).containsExactlyInAnyOrder("com.example:lib", "com.example:app");
        assertThat(order.indexOf("com.example:lib")).isLessThan(order.indexOf("com.example:app"));
    }

    @Test
    void coordinator_workspace_root_without_sources_is_not_a_unit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["core"]
                """);
        Files.createDirectories(tmp.resolve("core/src"));
        Files.writeString(tmp.resolve("core/src/Main.java"), "class Main {}");
        Files.writeString(tmp.resolve("core/jk.toml"), """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        // Root has no src/ → coordinator only; only the module is a unit.
        assertThat(coords(r)).containsExactly("com.example:core");
    }

    /**
     * A coordinator root with `.jk/` is a unit even though it compiles nothing: it has an
     * `after-build` script, and the graph is the only thing that can run it once, after everything
     * else. Before JK-1058 the directory was silently ignored.
     */
    @Test
    void coordinator_root_with_build_logic_is_a_unit_ordered_last(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        for (String m : List.of("core", "app")) {
            Files.createDirectories(tmp.resolve(m + "/src"));
            Files.writeString(tmp.resolve(m + "/src/Main.java"), "class Main {}");
            Files.writeString(tmp.resolve(m + "/jk.toml"), """
                    group = "com.example"
                    name  = "%s"
                    version = "1.0.0"
                    jdk = 25
                    java = 25
                    """.formatted(m));
        }
        Files.createDirectories(tmp.resolve(".jk"));
        Files.writeString(tmp.resolve(".jk/after-build.groovy"), "// workspace check\n");

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        List<String> order = coords(r);
        assertThat(order).containsExactlyInAnyOrder("com.example:core", "com.example:app", "com.example:root");
        assertThat(order.indexOf("com.example:root"))
                .as("after-build means after every member")
                .isEqualTo(order.size() - 1);
        assertThat(order.stream().filter("com.example:root"::equals))
                .as("once per workspace, not once per member")
                .hasSize(1);
    }

    @Test
    void self_buildable_workspace_root_is_also_a_unit(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["core"]
                """);
        // Root carries its own sources → it is itself a build unit.
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}");
        Files.createDirectories(tmp.resolve("core/src"));
        Files.writeString(tmp.resolve("core/src/Core.java"), "class Core {}");
        Files.writeString(tmp.resolve("core/jk.toml"), """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactlyInAnyOrder("com.example:root", "com.example:core");
    }

    @Test
    void module_dependency_cycle_is_reported(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        Files.createDirectories(tmp.resolve("a"));
        Files.writeString(tmp.resolve("a/jk.toml"), """
                group = "com.example"
                name  = "a"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                b = { group = "com.example", name = "b", version = "1.0.0" }
                """);
        Files.createDirectories(tmp.resolve("b"));
        Files.writeString(tmp.resolve("b/jk.toml"), """
                group = "com.example"
                name  = "b"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                a = { group = "com.example", name = "a", version = "1.0.0" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).anyMatch(e -> e.contains("cycle"));
        assertThat(r.topoOrder()).isEmpty();
    }
}
