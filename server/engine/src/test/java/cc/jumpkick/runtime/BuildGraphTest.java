// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void a_sibling_named_only_in_test_scopes_is_a_test_edge_not_a_build_order_edge(@TempDir Path tmp) throws Exception {
        twoMembers(tmp, """
                [test-dependencies]
                core = { workspace = true, kind = "tests" }
                """);

        BuildGraph.Result r = resolve(tmp);

        Path core = BuildGraph.canonicalPath(tmp.resolve("core"));
        Path app = BuildGraph.canonicalPath(tmp.resolve("app"));
        assertThat(r.errors()).isEmpty();
        assertThat(r.edges().get(app))
                .as("app is admitted without waiting for core")
                .isEmpty();
        assertThat(r.testEdges().get(app)).containsExactly(core);
        assertThat(r.reads().get(app)).containsExactly(core);
        assertThat(r.testReads().testClasses().get(app)).containsExactly(core);
        assertThat(r.testReads().fixtures()).isEmpty();
    }

    @Test
    void a_sibling_in_a_main_and_a_test_table_is_a_build_order_edge_only(@TempDir Path tmp) throws Exception {
        twoMembers(tmp, """
                [dependencies]
                core = { workspace = true }

                [test-dependencies]
                core = { workspace = true, kind = "tests" }
                """);

        BuildGraph.Result r = resolve(tmp);

        Path core = BuildGraph.canonicalPath(tmp.resolve("core"));
        Path app = BuildGraph.canonicalPath(tmp.resolve("app"));
        assertThat(r.edges().get(app)).containsExactly(core);
        assertThat(r.testEdges()).doesNotContainKey(app);
        assertThat(r.testReads().testClasses().get(app))
                .as("the test classes it selects are still waited for")
                .containsExactly(core);
    }

    /**
     * The Gradle shape {@code jk import} writes for a fixtures module: infra's tests read arch's
     * fixtures, arch's fixtures and tests read infra's main, and app's main reads infra. Per source
     * set there is no loop, so the graph resolves; only app → infra orders modules.
     */
    @Test
    void members_whose_tests_read_each_other_resolve_without_a_cycle(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["infra", "arch", "app"]
                """);
        member(tmp, "infra", """
                [test-dependencies]
                arch = { workspace = true, fixtures = true }
                """);
        member(tmp, "arch", """
                [test]
                fixtures = true

                [test-dependencies]
                infra = { workspace = true }
                """);
        member(tmp, "app", """
                [dependencies]
                infra = { workspace = true }
                """);

        BuildGraph.Result r = resolve(tmp);

        Path infra = BuildGraph.canonicalPath(tmp.resolve("infra"));
        Path arch = BuildGraph.canonicalPath(tmp.resolve("arch"));
        Path app = BuildGraph.canonicalPath(tmp.resolve("app"));
        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactly("com.example:infra", "com.example:arch", "com.example:app");
        assertThat(r.edges()).containsEntry(infra, Set.of()).containsEntry(arch, Set.of());
        assertThat(r.edges().get(app)).containsExactly(infra);
        assertThat(r.testEdges()).containsEntry(infra, Set.of(arch)).containsEntry(arch, Set.of(infra));
        assertThat(r.testReads().fixtures()).containsExactly(Map.entry(infra, Set.of(arch)));
        assertThat(r.maxReadyWidth()).as("infra and arch start together").isEqualTo(2);
        Map<Path, Set<Path>> order = r.readOrder();
        assertThat(order.get(app)).containsExactly(infra);
        assertThat(order.getOrDefault(infra, Set.of()).size()
                        + order.getOrDefault(arch, Set.of()).size())
                .as("one of the two test edges closes the loop and is left out of the walk order")
                .isEqualTo(1);
        assertThat(BuildGraph.waves(Set.of(infra, arch, app), order).stream()
                        .mapToInt(List::size)
                        .sum())
                .as("the walk order reaches every unit")
                .isEqualTo(3);
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
     * else. Before the directory was silently ignored.
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
        Files.createDirectories(tmp.resolve(".jk/scripts"));
        Files.writeString(tmp.resolve(".jk/scripts/after-build.groovy"), "// workspace check\n");

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

        member(tmp, "c", """
                [dependencies]
                a = { workspace = true }
                """);
        Files.writeString(
                tmp.resolve("jk.toml"),
                Files.readString(tmp.resolve("jk.toml")).replace("[\"a\", \"b\"]", "[\"a\", \"b\", \"c\"]"));

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors())
                .as("the members in the loop and the tables closing it, not c that only waits on it")
                .containsExactly("workspace build graph has a cycle: a → b → a (a [dependencies] names b;"
                        + " b [dependencies] names a). Only a test-scope edge may close a loop between members.");
        assertThat(r.topoOrder()).isEmpty();
    }

    @Test
    void a_variant_values_dependency_on_a_member_orders_it_first(@TempDir Path tmp) throws Exception {
        twoMembers(tmp, """
                [variants.tier]
                default = "paid"
                [variants.tier.free]
                [variants.tier.paid]
                [variants.tier.paid.dependencies]
                core = { workspace = true }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(coords(r)).containsExactly("com.example:core", "com.example:app");
        assertThat(r.edges().get(BuildGraph.canonicalPath(tmp.resolve("app"))))
                .containsExactly(BuildGraph.canonicalPath(tmp.resolve("core")));
    }

    @Test
    void a_path_dependency_on_a_member_is_reported(@TempDir Path tmp) throws Exception {
        twoMembers(tmp, """
                [dependencies]
                core = { path = "../core" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors())
                .containsExactly("app: `core = { path = \"../core\" }` names workspace member com.example:core;"
                        + " write `core = { workspace = true }`");
        assertThat(r.topoOrder()).isEmpty();
    }

    @Test
    void a_path_dependency_outside_the_workspace_is_no_edge(@TempDir Path tmp) throws Exception {
        twoMembers(tmp, """
                [dependencies]
                ext = { path = "../../ext" }
                """);

        BuildGraph.Result r = resolve(tmp);

        assertThat(r.errors()).isEmpty();
        assertThat(r.edges().get(BuildGraph.canonicalPath(tmp.resolve("app")))).isEmpty();
    }

    private static void member(Path tmp, String name, String extra) throws IOException {
        Files.createDirectories(tmp.resolve(name));
        Files.writeString(tmp.resolve(name + "/jk.toml"), """
                group = "com.example"
                name  = "%s"
                version = "1.0.0"
                java = 25

                """.formatted(name) + extra);
    }

    /** A workspace of {@code core} and {@code app}, app's manifest ending in {@code appExtra}. */
    private static void twoMembers(Path tmp, String appExtra) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(tmp.resolve("core"));
        Files.writeString(tmp.resolve("core/jk.toml"), """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                java = 25
                """);
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                """ + appExtra);
    }
}
