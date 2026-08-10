// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

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
        // missing percent-decode for a whole release (JK-1607).
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> DependencyGraphModel.parseScopes("bogus"));
        assertThat(DependencyGraphModel.validScopes()).contains("main").contains("test");
    }

    @Test
    void standalone_declared_only_omits_transitive(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
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
                [project]
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
                [project]
                group = "com.acme"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib").resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "lib"
                version = "1"
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app").resolve("jk.toml"), """
                [project]
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
