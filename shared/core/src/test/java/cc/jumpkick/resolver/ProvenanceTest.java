// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvenanceTest {

    @Test
    void single_path() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock =
                lockOf(pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")), pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths).singleElement().satisfies(p -> assertThat(p.render())
                .isEqualTo("com.foo:root v1.0 -> com.foo:leaf v1.0"));
    }

    @Test
    void diamond_from_same_root_yields_one_shortest_path() {
        // root -> a -> leaf
        // -> b -> leaf
        // Same declared root: report one shortest route (not both diamond arms).
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths).singleElement().satisfies(p -> {
            assertThat(p.steps()).hasSize(3);
            assertThat(p.render()).startsWith("com.foo:root v1.0").endsWith("com.foo:leaf v1.0");
        });
    }

    @Test
    void a_root_behind_another_declared_root_still_gets_a_path() {
        // Declared root A depends on declared root B which depends on the target: the reverse BFS
        // must not stop at B — one shortest path per DISTINCT root.
        JkBuild project = projectWithMainDeps("com.foo:a", "com.foo:b");
        Lockfile lock = lockOf(
                pkg("com.foo:a", "1.0", List.of("com.foo:b@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");

        assertThat(paths).hasSize(2);
        assertThat(paths.stream().map(p -> p.steps().getFirst().module()).toList())
                .containsExactlyInAnyOrder("com.foo:a", "com.foo:b");
    }

    @Test
    void distinct_roots_each_get_a_path() {
        // rootA -> leaf
        // rootB -> mid -> leaf
        JkBuild project = projectWithMainDeps("com.foo:rootA", "com.foo:rootB");
        Lockfile lock = lockOf(
                pkg("com.foo:rootA", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:rootB", "1.0", List.of("com.foo:mid@1.0")),
                pkg("com.foo:mid", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths)
                .extracting(Provenance.Path::render)
                .containsExactly(
                        "com.foo:rootA v1.0 -> com.foo:leaf v1.0",
                        "com.foo:rootB v1.0 -> com.foo:mid v1.0 -> com.foo:leaf v1.0");
    }

    @Test
    void fan_in_through_shared_mid_does_not_multiply_paths() {
        // Two declared roots both go through rewrite-core-style fan-in before the leaf.
        // rootA -> mid -> a -> leaf
        // rootA -> mid -> b -> leaf (should not create 2 paths for rootA)
        // rootB -> mid -> leaf
        JkBuild project = projectWithMainDeps("com.foo:rootA", "com.foo:rootB");
        Lockfile lock = lockOf(
                pkg("com.foo:rootA", "1.0", List.of("com.foo:mid@1.0")),
                pkg("com.foo:rootB", "1.0", List.of("com.foo:mid@1.0")),
                pkg("com.foo:mid", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths).hasSize(2);
        assertThat(paths).allSatisfy(p -> assertThat(p.render()).contains("com.foo:leaf"));
        assertThat(paths.stream().map(p -> p.steps().getFirst().module()).toList())
                .containsExactlyInAnyOrder("com.foo:rootA", "com.foo:rootB");
    }

    @Test
    void returns_empty_when_target_absent() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.missing:thing");
        assertThat(paths).isEmpty();
    }

    @Test
    void target_is_a_declared_root_yields_single_step() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:root");
        assertThat(paths).singleElement().satisfies(p -> assertThat(p.render()).isEqualTo("com.foo:root v1.0"));
    }

    @Test
    void package_key_deps_match_ga_declared_roots() {
        // Lock rows use Maven package keys (g:a:jar:); declared roots are GA.
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root:jar:", "1.0", List.of("com.foo:mid:jar:@1.0")),
                pkg("com.foo:mid:jar:", "1.0", List.of("com.foo:leaf:jar:@1.0")),
                pkg("com.foo:leaf:jar:", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf:jar:");
        assertThat(paths).isNotEmpty();
        assertThat(paths.getFirst().render()).contains("com.foo:leaf").contains("com.foo:root");
    }

    @Test
    void workspace_root_uses_module_declared_deps(@TempDir Path dir) throws Exception {
        // Root jk.toml has no deps — only workspace modules do (the real monorepo case).
        Path modDir = dir.resolve("mod-a");
        Files.createDirectories(modDir);
        Files.writeString(modDir.resolve("jk.toml"), """
                group = "com.example"
                name = "mod-a"
                version = "0.1.0"

                [dependencies]
                root = { group = "com.foo", name = "root", version = "1.0" }
                """);

        JkBuild workspaceRoot = JkBuild.builder(new Project("com.example", "workspace", "0.1.0", 0))
                .workspace(new Workspace(List.of("mod-a")))
                .build();

        Lockfile lock = lockOf(
                pkg("com.foo:root:jar:", "1.0", List.of("com.foo:leaf:jar:@1.0")),
                pkg("com.foo:leaf:jar:", "1.0", List.of()));

        // Without projectDir: empty roots → only lock-top fallback still finds a path.
        List<Provenance.Path> withoutDir = Provenance.pathsTo(workspaceRoot, lock, "com.foo:leaf:jar:");
        assertThat(withoutDir).isNotEmpty();

        // With projectDir: stops at the module's declared root.
        List<Provenance.Path> withDir = Provenance.pathsTo(workspaceRoot, lock, "com.foo:leaf:jar:", dir);
        assertThat(withDir).singleElement().satisfies(p -> {
            assertThat(p.render()).contains("com.foo:root").contains("com.foo:leaf");
            assertThat(p.steps()).hasSize(2);
        });
    }

    @Test
    void lock_top_path_when_no_declared_roots() {
        // Empty project deps: still reverse-walk to the lockfile top (not a blank stale message).
        JkBuild project = projectWithMainDeps();
        Lockfile lock =
                lockOf(pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")), pkg("com.foo:leaf", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:leaf");
        assertThat(paths).singleElement().satisfies(p -> assertThat(p.render())
                .isEqualTo("com.foo:root v1.0 -> com.foo:leaf v1.0"));
    }

    @Test
    void true_orphan_returns_empty() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(pkg("com.foo:root", "1.0", List.of()), pkg("com.foo:orphan", "1.0", List.of()));

        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, "com.foo:orphan");
        assertThat(paths).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private static JkBuild projectWithMainDeps(String... modules) {
        var deps = new ArrayList<Dependency>();
        for (String m : modules) {
            deps.add(new Dependency(m, new VersionSelector.Exact("=1.0", "1.0")));
        }
        return new JkBuild(
                new Project("com.example", "widget", "0.1.0", 0), new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));
    }

    private static Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Artifact pkg(String module, String version, List<String> deps) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", "sha256:dummy", null, deps);
    }
}
