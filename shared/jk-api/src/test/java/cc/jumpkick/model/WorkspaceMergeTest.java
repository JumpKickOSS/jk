// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkspaceMergeTest {

    @Test
    void merge_combines_module_deps_into_root() {
        JkBuild root = newProject("root", Map.of(Scope.MAIN, List.of(dep("a", "com.foo:a", "1.0"))));
        JkBuild moduleA = newProject("a", Map.of(Scope.MAIN, List.of(dep("b", "com.foo:b", "2.0"))));
        JkBuild moduleB = newProject(
                "b", Map.of(Scope.TEST, List.of(dep("junit-jupiter", "org.junit.jupiter:junit-jupiter", "6.1.0"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(moduleA, moduleB));

        assertThat(merged.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder("com.foo:a", "com.foo:b");
        assertThat(merged.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");
    }

    @Test
    void root_declaration_wins_on_module_conflict() {
        JkBuild root = newProject("root", Map.of(Scope.MAIN, List.of(dep("bar", "com.foo:bar", "3.0"))));
        JkBuild module = newProject("module", Map.of(Scope.MAIN, List.of(dep("bar", "com.foo:bar", "1.0"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(module));
        Dependency surviving = merged.dependencies().of(Scope.MAIN).getFirst();
        assertThat(surviving.version().raw()).isEqualTo("3.0");
    }

    @Test
    void empty_modules_returns_root_unchanged() {
        JkBuild root = newProject("root", Map.of());
        assertThat(WorkspaceMerge.merge(root, List.of())).isSameAs(root);
    }

    @Test
    void workspace_dep_resolves_against_sibling_artifact() {
        // Module dep `jk-core.workspace = true` materializes as a placeholder
        // module `workspace:jk-core`; the merge step rewrites it to the
        // sibling's actual coord (and then dedupes since it's internal).
        JkBuild root = workspaceRoot("jk", List.of("jk-core", "jk-cli"));
        JkBuild core = newProject("jk-core", Map.of());
        JkBuild cli = newProject("jk-cli", Map.of(Scope.MAIN, List.of(workspacePlaceholder("jk-core"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(core, cli));

        // The placeholder gets rewritten to cc.jumpkick:jk-core, which is
        // then dropped as workspace-internal.
        assertThat(merged.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void workspace_dep_resolves_against_workspace_dependencies_when_no_sibling() {
        Workspace.WorkspaceDependency wsDep = new Workspace.WorkspaceDependency(
                "org.junit.jupiter", "junit-jupiter", VersionSelector.parse("6.1.0"), null);
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "jk", "0.1.0", 0))
                .workspace(new Workspace(List.of("core"), Map.of("junit-jupiter", wsDep)))
                .build();

        JkBuild core = newProject("jk-core", Map.of(Scope.TEST, List.of(workspacePlaceholder("junit-jupiter"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(core));

        var testDeps = merged.dependencies().of(Scope.TEST);
        assertThat(testDeps).hasSize(1);
        assertThat(testDeps.getFirst().library()).isEqualTo("junit-jupiter");
        assertThat(testDeps.getFirst().module()).isEqualTo("org.junit.jupiter:junit-jupiter");
    }

    @Test
    void unresolved_workspace_dep_throws() {
        JkBuild root = workspaceRoot("jk", List.of("core"));
        JkBuild core = newProject("jk-core", Map.of(Scope.MAIN, List.of(workspacePlaceholder("does-not-exist"))));

        assertThatThrownBy(() -> WorkspaceMerge.merge(root, List.of(core)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void merge_keeps_jar_and_test_jar_of_same_ga_as_distinct_rows() {
        // LockOrchestrator roots per packageKey() (e65323f6): the main jar and the
        // test-jar of one GA are different packages. The workspace union feeding it
        // must not collapse them to first-seen-wins on bare GA.
        Dependency plain = dep("helpers", "com.acme:helpers", "1.2.3");
        Dependency testsKind = dep("helpers-tests", "com.acme:helpers", "1.2.3").withKind(DependencyKind.TESTS);
        JkBuild root = newProject("root", Map.of());
        JkBuild moduleA = newProject("a", Map.of(Scope.TEST, List.of(plain)));
        JkBuild moduleB = newProject("b", Map.of(Scope.TEST, List.of(testsKind)));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(moduleA, moduleB));

        assertThat(merged.dependencies().of(Scope.TEST))
                .extracting(Dependency::packageKey)
                .containsExactlyInAnyOrder("com.acme:helpers:jar:", "com.acme:helpers:test-jar:tests");
    }

    @Test
    void merge_still_dedupes_same_package_across_modules() {
        Dependency inA = dep("helpers", "com.acme:helpers", "1.2.3").withKind(DependencyKind.TESTS);
        Dependency inB = dep("helpers", "com.acme:helpers", "1.2.3").withKind(DependencyKind.TESTS);
        JkBuild root = newProject("root", Map.of());
        JkBuild moduleA = newProject("a", Map.of(Scope.TEST, List.of(inA)));
        JkBuild moduleB = newProject("b", Map.of(Scope.TEST, List.of(inB)));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(moduleA, moduleB));

        assertThat(merged.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void resolve_sibling_coordinates_preserves_tests_kind() {
        // applyToModule drops sibling edges (lock path). resolveSiblingCoordinates keeps them
        // (publish/POM path) and must retain kind so test-jar edges survive rewrite.
        JkBuild root = workspaceRoot("jk", List.of("lib", "app"));
        JkBuild lib = newProject("lib", Map.of());
        Dependency testsEdge = workspacePlaceholder("lib").withKind(DependencyKind.TESTS);
        JkBuild app = newProject(
                "app",
                Map.of(
                        Scope.MAIN, List.of(workspacePlaceholder("lib")),
                        Scope.TEST, List.of(testsEdge)));

        JkBuild rewritten = WorkspaceMerge.resolveSiblingCoordinates(root, app, List.of(lib, app));
        assertThat(rewritten.dependencies().of(Scope.TEST))
                .anyMatch(d -> d.isTestsKind() && d.module().equals("cc.jumpkick:lib"));
        assertThat(rewritten.dependencies().of(Scope.MAIN))
                .filteredOn(d -> d.module().equals("cc.jumpkick:lib"))
                .allMatch(d -> d.kind() == DependencyKind.MAIN);
    }

    @Test
    void variants_survive_apply_to_module_and_union_into_lock_scopes() {
        // A flavored module: its [variants] block must ride through the merge (the finding-5
        // class of bug), and lock scopes must see the UNION of every value's dep overlays —
        // the module's own AND its siblings' (variant-only externals fold transitively).
        Variants variants = new Variants(List.of(new Variants.Dimension(
                "contentType",
                null,
                Map.of(
                        "demo",
                                new Variants.Value(
                                        List.of("src/demo/java"),
                                        Map.of(Scope.MAIN, List.of(dep("demo-only", "com.foo:demo-only", "1.0"))),
                                        Map.of()),
                        "prod",
                                new Variants.Value(
                                        List.of(),
                                        Map.of(Scope.MAIN, List.of(dep("prod-only", "com.foo:prod-only", "1.0"))),
                                        Map.of())))));
        JkBuild root = workspaceRoot("jk", List.of("app", "network"));
        JkBuild network = JkBuild.builder(new Project("cc.jumpkick", "network", "0.1.0", 0))
                .variants(variants)
                .build();
        JkBuild app = newProject("app", Map.of(Scope.MAIN, List.of(workspacePlaceholder("network"))));

        JkBuild networkScope = WorkspaceMerge.applyToModule(root, network, List.of(app, network));
        assertThat(networkScope.variants().dimension("contentType")).isPresent();
        assertThat(networkScope.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder("com.foo:demo-only", "com.foo:prod-only");

        // The app's lock scope folds the sibling's variant-only externals transitively.
        JkBuild appScope = WorkspaceMerge.applyToModule(root, app, List.of(app, network));
        assertThat(appScope.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .contains("com.foo:demo-only", "com.foo:prod-only");

        // The merged-root lock (PRD §13.2) sees the union too.
        JkBuild merged = WorkspaceMerge.merge(root, List.of(app, network));
        assertThat(merged.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder("com.foo:demo-only", "com.foo:prod-only");
    }

    // --- helpers -----------------------------------------------------------

    private static JkBuild newProject(String artifact, Map<Scope, List<Dependency>> depsByScope) {
        EnumMap<Scope, List<Dependency>> by = new EnumMap<>(Scope.class);
        depsByScope.forEach(by::put);
        return new JkBuild(new Project("cc.jumpkick", artifact, "0.1.0", 0), new JkBuild.Dependencies(by));
    }

    private static JkBuild workspaceRoot(String artifact, List<String> modules) {
        return JkBuild.builder(new Project("cc.jumpkick", artifact, "0.1.0", 0))
                .workspace(new Workspace(modules))
                .build();
    }

    private static Dependency dep(String name, String module, String version) {
        return Dependency.of(name, module, VersionSelector.parse(version));
    }

    /**
     * Mirrors what {@code JkBuildParser} emits for {@code <name>.workspace = true} when there is no
     * matching [workspace.dependencies] entry at parse time.
     */
    private static Dependency workspacePlaceholder(String name) {
        return new Dependency(name, "workspace:" + name, new VersionSelector.Latest("workspace"), null, null, false);
    }
}
