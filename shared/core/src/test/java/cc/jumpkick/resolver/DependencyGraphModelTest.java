// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyGraphModelTest {

    @Test
    void parseScopes_defaults_to_export_main_runtime_like_jk_tree() {
        assertThat(DependencyGraphModel.parseScopes(null)).containsExactly(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
        assertThat(DependencyGraphModel.parseScopes("")).containsExactly(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
        assertThat(DependencyGraphModel.parseScopes("test,main")).containsExactly(Scope.MAIN, Scope.TEST);
        // An unknown token is an error, not a silent fallback: swallowing it hid the endpoint's
        // missing percent-decode for a whole release.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> DependencyGraphModel.parseScopes("bogus"));
        assertThat(DependencyGraphModel.validScopes()).contains("main").contains("test");
    }

    @Test
    void standalone_declared_only_omits_transitive(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                root = { group = "com.foo", name = "root", version = "1.0" }
                """);
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "com.foo:root"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dummy"
                scopes   = ["main"]
                deps     = ["com.foo:leaf@1.0"]

                [[artifact]]
                name     = "com.foo:leaf"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dummy"
                scopes   = ["main"]
                deps     = []
                """);

        var direct = DependencyGraphModel.forProjectDir(dir, List.of(Scope.MAIN), false);
        assertThat(direct.workspace()).isFalse();
        assertThat(labels(direct)).containsExactlyInAnyOrder("com.example:app", "com.foo:root");
        assertThat(kinds(direct)).containsEntry("com.example:app", "module");
        assertThat(kinds(direct)).containsEntry("com.foo:root", "declared");
        assertThat(labels(direct)).doesNotContain("com.foo:leaf");

        var full = DependencyGraphModel.forProjectDir(dir, List.of(Scope.MAIN), true);
        assertThat(labels(full)).contains("com.foo:leaf");
        assertThat(kinds(full)).containsEntry("com.foo:leaf", "transitive");
        assertThat(kinds(full)).containsEntry("com.foo:root", "declared");
    }

    @Test
    void test_scope_only_when_selected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1"

                [dependencies]
                mainlib = { group = "com.foo", name = "mainlib", version = "1" }

                [test-dependencies]
                testlib = { group = "com.foo", name = "testlib", version = "1" }
                """);

        var mainOnly = DependencyGraphModel.forProjectDir(dir, List.of(Scope.MAIN), false);
        assertThat(labels(mainOnly)).contains("com.foo:mainlib");
        assertThat(labels(mainOnly)).doesNotContain("com.foo:testlib");

        var testOnly = DependencyGraphModel.forProjectDir(dir, List.of(Scope.TEST), false);
        assertThat(labels(testOnly)).contains("com.foo:testlib");
        assertThat(labels(testOnly)).doesNotContain("com.foo:mainlib");
    }

    @Test
    void workspace_modules_and_external_declared(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("jk.toml"), """
                group = "com.acme"
                name = "lib"
                version = "1"
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app").resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "1"

                [dependencies]
                lib = { workspace = true }
                jgit = { group = "org.eclipse.jgit", name = "org.eclipse.jgit", version = "6.0" }
                """);

        var g = DependencyGraphModel.forProjectDir(root, List.of(Scope.MAIN), false);
        assertThat(g.workspace()).isTrue();
        assertThat(labels(g)).contains("com.acme:lib", "com.acme:app", "org.eclipse.jgit:org.eclipse.jgit");
        assertThat(kinds(g)).containsEntry("com.acme:lib", "module");
        assertThat(kinds(g)).containsEntry("com.acme:app", "module");
        assertThat(kinds(g)).containsEntry("org.eclipse.jgit:org.eclipse.jgit", "declared");
        // app → lib (workspace) and app → jgit
        Set<String> pairs = g.edges().stream()
                .map(e -> labelOf(g, e.from()) + "→" + labelOf(g, e.to()))
                .collect(Collectors.toSet());
        assertThat(pairs)
                .contains("com.acme:app→com.acme:lib")
                .contains("com.acme:app→org.eclipse.jgit:org.eclipse.jgit");
    }

    // one default scope set, shared by jk tree and the graph endpoint from one definition.
    @Test
    void default_scopes_are_the_jk_tree_defaults_from_one_definition(@TempDir Path dir) throws Exception {
        assertThat(DependencyGraphModel.defaultScopes()).isEqualTo(DependencyTreeStyle.defaultScopeOrder());
        assertThat(DependencyGraphModel.parseScopes(null)).isEqualTo(DependencyTreeStyle.defaultScopeOrder());

        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"
                """);
        // Null/empty scope list falls back to the same shared default, not a private one.
        var g = DependencyGraphModel.forProjectDir(dir, null, false);
        assertThat(g.scopes()).containsExactly("export", "main", "runtime");
    }

    /**
     * a declared external whose TOML table key equals a workspace module's name must stay
     * an external artifact — the coordinate decides, exactly like {@code ModuleOrder}. The old
     * {@code idByName.get(d.library())} fallback drew a module edge and hid the published artifact.
     */
    @Test
    void external_dep_whose_table_key_matches_a_module_name_stays_external(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["jk-api", "compat"]
                """);
        Files.createDirectories(root.resolve("jk-api"));
        Files.writeString(root.resolve("jk-api").resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-api"
                version = "0.10.0"
                """);
        Files.createDirectories(root.resolve("compat"));
        // Table key "jk-api", but the coordinate names a DIFFERENT (published) artifact group.
        Files.writeString(root.resolve("compat").resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "compat"
                version = "1"

                [dependencies]
                jk-api = { group = "cc.jumpkick.released", name = "jk-api", version = "0.9.0" }
                """);

        var g = DependencyGraphModel.forProjectDir(root, List.of(Scope.MAIN), false);
        // The external artifact appears as its own node …
        assertThat(labels(g)).contains("cc.jumpkick.released:jk-api");
        assertThat(kinds(g)).containsEntry("cc.jumpkick.released:jk-api", "declared");
        // … and no fabricated module edge compat → jk-api exists.
        Set<String> pairs = g.edges().stream()
                .map(e -> labelOf(g, e.from()) + "→" + labelOf(g, e.to()))
                .collect(Collectors.toSet());
        assertThat(pairs).contains("cc.jumpkick:compat→cc.jumpkick.released:jk-api");
        assertThat(pairs).doesNotContain("cc.jumpkick:compat→cc.jumpkick:jk-api");
    }

    // a workspace member whose jk.toml is gone is an ERROR naming the module, never an
    // empty graph ("your project has no dependencies").
    @Test
    void missing_module_jk_toml_throws_instead_of_returning_empty(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "g"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["gone"]
                """);
        org.junit.jupiter.api.Assertions.assertThrows(
                JkBuildParseException.class,
                () -> DependencyGraphModel.forProjectDir(root, List.of(Scope.MAIN), false),
                "missing module must surface as an error");
        try {
            DependencyGraphModel.forProjectDir(root, List.of(Scope.MAIN), false);
        } catch (JkBuildParseException e) {
            assertThat(e.getMessage()).contains("gone");
        }
    }

    @Test
    void malformed_toml_throws_instead_of_returning_empty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "not [ valid toml ===");
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> DependencyGraphModel.forProjectDir(dir, List.of(Scope.MAIN), false));
    }

    @Test
    void absent_jk_toml_is_still_an_empty_graph_not_an_error(@TempDir Path dir) throws Exception {
        var g = DependencyGraphModel.forProjectDir(dir, List.of(Scope.MAIN), false);
        assertThat(g.nodes()).isEmpty();
        assertThat(g.truncated()).isFalse();
    }

    /**
     * the transitive closure is walked once (shared seen), and expansion stops at the
     * node cap with {@code truncated} set instead of handing the browser an unbounded graph.
     */
    @Test
    void transitive_expansion_shares_the_walk_and_truncates_at_the_cap(@TempDir Path dir) throws Exception {
        int artifacts = DependencyGraphModel.MAX_NODES + 100;
        StringBuilder toml = new StringBuilder("""
                group = "com.bench"
                name = "app"
                version = "1"

                [dependencies]
                d0 = { group = "com.bench", name = "a0", version = "1" }
                d1 = { group = "com.bench", name = "a1", version = "1" }
                """);
        Files.writeString(dir.resolve("jk.toml"), toml.toString());
        StringBuilder lock = new StringBuilder("""
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        for (int i = 0; i < artifacts; i++) {
            lock.append("\n[[artifact]]\n")
                    .append("name = \"com.bench:a")
                    .append(i)
                    .append("\"\n")
                    .append("version = \"1\"\n")
                    .append("source = \"central+https://repo.maven.apache.org/maven2/\"\n")
                    .append("checksum = \"sha256:dummy\"\n")
                    .append("scopes = [\"main\"]\n");
            if (i + 1 < artifacts) {
                lock.append("deps = [\"com.bench:a").append(i + 1).append("@1\"]\n");
            } else {
                lock.append("deps = []\n");
            }
        }
        Files.writeString(dir.resolve("jk-lock.toml"), lock.toString());

        var g = DependencyGraphModel.forProjectDir(dir, List.of(Scope.MAIN), true);
        assertThat(g.truncated()).isTrue();
        // Module node + externals: expansion never grows past the cap (plus the declared roots).
        assertThat(g.nodes().size()).isLessThanOrEqualTo(DependencyGraphModel.MAX_NODES + 3);
        assertThat(g.edges().size()).isLessThanOrEqualTo(DependencyGraphModel.MAX_EDGES);

        // Both declared roots reach the shared chain; the second walk is memoized, not repeated —
        // and crucially the deduped result still contains the diamond edges from both roots.
        Set<String> pairs = g.edges().stream()
                .map(e -> labelOf(g, e.from()) + "→" + labelOf(g, e.to()))
                .collect(Collectors.toSet());
        assertThat(pairs)
                .contains("com.bench:app→com.bench:a0")
                .contains("com.bench:app→com.bench:a1")
                .contains("com.bench:a0→com.bench:a1")
                .contains("com.bench:a1→com.bench:a2");
    }

    /**
     * one GA declared as both the main jar and {@code kind = "tests"} keeps two distinct
     * nodes (package-key identity), two edges, and both lock subtrees expanded.
     */
    @Test
    void one_ga_two_kinds_is_two_nodes_with_both_subtrees(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"

                [test-dependencies]
                lib = { group = "com.foo", name = "lib", version = "1" }
                lib-tests = { group = "com.foo", name = "lib", version = "1", kind = "tests" }
                """);
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "com.foo:lib"
                version  = "1"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dummy"
                scopes   = ["test"]
                deps     = ["com.foo:main-leaf@1"]

                [[artifact]]
                name     = "com.foo:lib:test-jar:tests"
                version  = "1"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dummy"
                scopes   = ["test"]
                deps     = ["com.foo:test-leaf@1"]

                [[artifact]]
                name     = "com.foo:main-leaf"
                version  = "1"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dummy"
                scopes   = ["test"]
                deps     = []

                [[artifact]]
                name     = "com.foo:test-leaf"
                version  = "1"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dummy"
                scopes   = ["test"]
                deps     = []
                """);

        var g = DependencyGraphModel.forProjectDir(dir, List.of(Scope.TEST), true);
        // Two distinct nodes for the one GA (label carries the classifier/type badge) …
        long libNodes = g.nodes().stream()
                .filter(n -> n.label().startsWith("com.foo:lib"))
                .count();
        assertThat(libNodes).isEqualTo(2);
        // … two declared edges from the project …
        String rootId = g.nodes().stream()
                .filter(n -> "module".equals(n.kind()))
                .map(DependencyGraphModel.Node::id)
                .findFirst()
                .orElseThrow();
        long edgesFromRoot =
                g.edges().stream().filter(e -> e.from().equals(rootId)).count();
        assertThat(edgesFromRoot).isEqualTo(2);
        // … and BOTH subtrees expanded, not just whichever the bare GA resolved to.
        assertThat(labels(g)).contains("com.foo:main-leaf").contains("com.foo:test-leaf");
    }

    /** the workspace root's own [dependencies] appear, hanging off a root node. */
    @Test
    void workspace_root_dependencies_appear_with_a_root_node(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["lib"]

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("jk.toml"), """
                group = "com.acme"
                name = "lib"
                version = "1"
                """);

        var g = DependencyGraphModel.forProjectDir(root, List.of(Scope.MAIN), false);
        assertThat(kinds(g)).containsEntry("com.acme:ws", "module");
        assertThat(labels(g)).contains("com.google.guava:guava");
        Set<String> pairs = g.edges().stream()
                .map(e -> labelOf(g, e.from()) + "→" + labelOf(g, e.to()))
                .collect(Collectors.toSet());
        assertThat(pairs).contains("com.acme:ws→com.google.guava:guava");
        // The root node carries the "." path so the UI can tell it apart from members.
        assertThat(g.nodes().stream()
                        .filter(n -> "com.acme:ws".equals(n.label()))
                        .findFirst()
                        .orElseThrow()
                        .path())
                .isEqualTo(".");
    }

    /** a graph built from a MODULE dir draws sibling deps as modules, not externals. */
    @Test
    void module_dir_draws_siblings_as_modules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("jk.toml"), """
                group = "com.acme"
                name = "lib"
                version = "1"
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app").resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "1"

                [dependencies]
                lib = { workspace = true }
                """);

        var g = DependencyGraphModel.forProjectDir(root.resolve("app"), List.of(Scope.MAIN), false);
        assertThat(g.workspace()).isFalse();
        assertThat(kinds(g)).containsEntry("com.acme:app", "module");
        // The sibling is a module node (with a path and its version), NOT a version-less external.
        assertThat(kinds(g)).containsEntry("com.acme:lib", "module");
        var lib = g.nodes().stream()
                .filter(n -> "com.acme:lib".equals(n.label()))
                .findFirst()
                .orElseThrow();
        assertThat(lib.version()).isEqualTo("1");
        assertThat(lib.path()).isNotNull();
        Set<String> pairs = g.edges().stream()
                .map(e -> labelOf(g, e.from()) + "→" + labelOf(g, e.to()))
                .collect(Collectors.toSet());
        assertThat(pairs).contains("com.acme:app→com.acme:lib");
        // Unreferenced siblings are not dragged in.
        assertThat(labels(g)).doesNotContain("com.acme:ws");
    }

    private static Set<String> labels(DependencyGraphModel.Graph g) {
        return g.nodes().stream().map(DependencyGraphModel.Node::label).collect(Collectors.toSet());
    }

    private static Map<String, String> kinds(DependencyGraphModel.Graph g) {
        return g.nodes().stream()
                .collect(Collectors.toMap(DependencyGraphModel.Node::label, DependencyGraphModel.Node::kind));
    }

    private static String labelOf(DependencyGraphModel.Graph g, String id) {
        return g.nodes().stream()
                .filter(n -> n.id().equals(id))
                .map(DependencyGraphModel.Node::label)
                .findFirst()
                .orElse(id);
    }
}
