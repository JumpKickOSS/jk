// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.net.URI;
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
    void merge_carries_the_roots_resolve_policies() {
        JkBuild root = workspaceRoot("jk", List.of("a")).withBuild(BuildBlock.EMPTY.withPinPolicy(PinPolicy.NEAREST));
        JkBuild module = newProject("a", Map.of(Scope.MAIN, List.of(dep("b", "com.foo:b", "2.0"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(module));

        assertThat(merged.build().pinPolicy()).isEqualTo(PinPolicy.NEAREST);
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

    /** The shared entry's exclusions come first, the edge's own after, each once. */
    @Test
    void a_shared_dependencys_own_exclusions_join_the_edges() {
        Workspace.WorkspaceDependency guavaDep = new Workspace.WorkspaceDependency(
                "com.google.guava",
                "guava",
                VersionSelector.parse("33.4.8-jre"),
                null,
                List.of("com.google.errorprone:*", "com.google.guava:listenablefuture"));
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "jk", "0.1.0", 0))
                .workspace(new Workspace(List.of("jk-cli"), Map.of("guava", guavaDep)))
                .build();
        JkBuild cli = newProject(
                "jk-cli",
                Map.of(
                        Scope.MAIN,
                        List.of(workspacePlaceholder("guava")
                                .withExclusions(
                                        List.of("com.google.guava:listenablefuture", "org.checkerframework:*")))));
        JkBuild merged = WorkspaceMerge.merge(root, List.of(cli));

        Dependency guava = merged.dependencies().of(Scope.MAIN).getFirst();
        assertThat(guava.exclusions())
                .containsExactly(
                        "com.google.errorprone:*", "com.google.guava:listenablefuture", "org.checkerframework:*");
    }

    @Test
    void a_workspace_edge_keeps_its_exclusions_when_it_resolves_to_a_shared_dependency() {
        Workspace.WorkspaceDependency guavaDep = new Workspace.WorkspaceDependency(
                "com.google.guava", "guava", VersionSelector.parse("33.4.8-jre"), null);
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "jk", "0.1.0", 0))
                .workspace(new Workspace(List.of("jk-cli"), Map.of("guava", guavaDep)))
                .build();
        JkBuild cli = newProject(
                "jk-cli",
                Map.of(
                        Scope.MAIN,
                        List.of(workspacePlaceholder("guava")
                                .withExclusions(List.of("com.google.guava:listenablefuture")))));
        JkBuild merged = WorkspaceMerge.merge(root, List.of(cli));

        Dependency guava = merged.dependencies().of(Scope.MAIN).getFirst();
        assertThat(guava.module()).isEqualTo("com.google.guava:guava");
        assertThat(guava.exclusions()).containsExactly("com.google.guava:listenablefuture");
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

    /** Two siblings named `edqs`; the edge's group picks one, and the other's externals stay out. */
    @Test
    void group_qualified_workspace_dep_resolves_to_the_member_with_that_group() {
        JkBuild root = workspaceRoot("tb", List.of("common/edqs", "edqs", "application"));
        JkBuild commonEdqs = new JkBuild(
                new Project("cc.jumpkick.common", "edqs", "0.1.0", 0),
                new JkBuild.Dependencies(new EnumMap<>(Map.of(Scope.MAIN, List.of(dep("a", "com.foo:a", "1.0"))))));
        JkBuild edqs = newProject("edqs", Map.of(Scope.MAIN, List.of(dep("b", "com.foo:b", "2.0"))));
        JkBuild application = newProject(
                "application", Map.of(Scope.MAIN, List.of(Dependency.workspace("edqs", "cc.jumpkick.common"))));

        JkBuild resolved = WorkspaceMerge.resolveSiblingCoordinates(root, application, List.of(commonEdqs, edqs));
        assertThat(resolved.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("cc.jumpkick.common:edqs");

        JkBuild folded = WorkspaceMerge.applyToModule(root, application, List.of(commonEdqs, edqs));
        assertThat(folded.dependencies().of(Scope.MAIN))
                .as("only the chosen member's externals fold in")
                .extracting(Dependency::module)
                .containsExactly("com.foo:a");

        JkBuild merged = WorkspaceMerge.merge(root, List.of(commonEdqs, edqs, application));
        assertThat(merged.dependencies().of(Scope.MAIN))
                .as("the one-manifest lock fold resolves the qualified edge too")
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder("com.foo:a", "com.foo:b");
    }

    @Test
    void group_qualified_workspace_dep_to_an_absent_group_throws() {
        JkBuild root = workspaceRoot("tb", List.of("edqs", "application"));
        JkBuild edqs = newProject("edqs", Map.of());
        JkBuild application =
                newProject("application", Map.of(Scope.MAIN, List.of(Dependency.workspace("edqs", "org.elsewhere"))));

        assertThatThrownBy(() -> WorkspaceMerge.merge(root, List.of(edqs, application)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.elsewhere:edqs");
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
    void resolve_sibling_coordinates_preserves_fixtures_flag() {
        JkBuild root = workspaceRoot("jk", List.of("lib", "app"));
        JkBuild lib = newProject("lib", Map.of());
        Dependency fixturesEdge = workspacePlaceholder("lib").withFixtures(true);
        JkBuild app = newProject(
                "app",
                Map.of(
                        Scope.MAIN, List.of(workspacePlaceholder("lib")),
                        Scope.TEST, List.of(fixturesEdge)));

        JkBuild rewritten = WorkspaceMerge.resolveSiblingCoordinates(root, app, List.of(lib, app));
        assertThat(rewritten.dependencies().of(Scope.TEST))
                .anyMatch(d -> d.isFixtures() && d.module().equals("cc.jumpkick:lib"));
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

    @Test
    void a_members_repositories_join_the_workspaces_keyed_by_id() {
        RepositorySpec central = new RepositorySpec("central", URI.create("https://repo.example/central/"));
        RepositorySpec jitpack = new RepositorySpec("jitpack", URI.create("https://jitpack.example/"));
        RepositorySpec confluent =
                new RepositorySpec("confluent", URI.create("https://packages.confluent.example/maven/"));
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "root", "0.1.0", 0))
                .workspace(new Workspace(List.of("a", "b")))
                .repositories(List.of(central))
                .build();
        JkBuild a = JkBuild.builder(new Project("cc.jumpkick", "a", "0.1.0", 0))
                .repositories(List.of(jitpack, central))
                .build();
        JkBuild b = JkBuild.builder(new Project("cc.jumpkick", "b", "0.1.0", 0))
                .repositories(List.of(confluent, jitpack))
                .build();

        JkBuild merged = WorkspaceMerge.merge(root, List.of(a, b));

        assertThat(merged.repositories()).containsExactly(central, jitpack, confluent);
    }

    /** Scheme and host case and an explicit default port do not make a second repository of one origin. */
    @Test
    void one_repository_spelled_with_host_case_or_its_default_port_is_one_repository() {
        RepositorySpec plain = new RepositorySpec("confluent", URI.create("https://packages.confluent.example/maven/"));
        RepositorySpec loud =
                new RepositorySpec("confluent", URI.create("HTTPS://Packages.Confluent.example:443/maven"));
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "root", "0.1.0", 0))
                .workspace(new Workspace(List.of("a", "b")))
                .build();
        JkBuild a = JkBuild.builder(new Project("cc.jumpkick", "a", "0.1.0", 0))
                .repositories(List.of(plain))
                .build();
        JkBuild b = JkBuild.builder(new Project("cc.jumpkick", "b", "0.1.0", 0))
                .repositories(List.of(loud))
                .build();

        assertThat(WorkspaceMerge.merge(root, List.of(a, b)).repositories()).containsExactly(plain);
    }

    @Test
    void one_repository_spelled_with_and_without_its_trailing_slash_is_one_repository() {
        RepositorySpec bare = new RepositorySpec("confluent", URI.create("https://packages.confluent.example/maven"));
        RepositorySpec slashed =
                new RepositorySpec("confluent", URI.create("https://packages.confluent.example/maven/"));
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "root", "0.1.0", 0))
                .workspace(new Workspace(List.of("a", "b")))
                .build();
        JkBuild a = JkBuild.builder(new Project("cc.jumpkick", "a", "0.1.0", 0))
                .repositories(List.of(bare))
                .build();
        JkBuild b = JkBuild.builder(new Project("cc.jumpkick", "b", "0.1.0", 0))
                .repositories(List.of(slashed))
                .build();

        JkBuild merged = WorkspaceMerge.merge(root, List.of(a, b));

        assertThat(merged.repositories())
                .as("the first spelling stands for both")
                .containsExactly(bare);
    }

    @Test
    void one_repository_id_at_two_urls_is_refused_naming_both_modules() {
        JkBuild root = JkBuild.builder(new Project("cc.jumpkick", "root", "0.1.0", 0))
                .workspace(new Workspace(List.of("a", "b")))
                .build();
        JkBuild a = JkBuild.builder(new Project("cc.jumpkick", "a", "0.1.0", 0))
                .repositories(List.of(new RepositorySpec("mirror", URI.create("https://one.example/m2/"))))
                .build();
        JkBuild b = JkBuild.builder(new Project("cc.jumpkick", "b", "0.1.0", 0))
                .repositories(List.of(new RepositorySpec("mirror", URI.create("https://two.example/m2/"))))
                .build();

        assertThatThrownBy(() -> WorkspaceMerge.merge(root, List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[repositories] mirror")
                .hasMessageContaining("cc.jumpkick:a")
                .hasMessageContaining("https://one.example/m2/")
                .hasMessageContaining("cc.jumpkick:b")
                .hasMessageContaining("https://two.example/m2/");
    }

    /**
     * A sibling's {@code optional = true} dependency is the sibling's own, as a published POM's
     * optional edge is: the consumer's lock manifest folds the sibling's other main dependencies
     * and leaves that one out.
     */
    @Test
    void a_siblings_optional_dependency_does_not_fold_into_its_consumer() {
        JkBuild root = workspaceRoot("ws", List.of("lib", "app"));
        JkBuild lib = newProject(
                "lib",
                Map.of(
                        Scope.MAIN,
                        List.of(
                                dep("core", "com.foo:core", "1.0"),
                                dep("mysql", "com.foo:mysql", "1.0").withOptional(true))));
        JkBuild app = newProject("app", Map.of(Scope.MAIN, List.of(workspacePlaceholder("lib"))));

        JkBuild scope = WorkspaceMerge.applyToModule(root, app, List.of(lib, app));

        assertThat(scope.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.foo:core");
    }

    /**
     * The merged manifest carries a member's optional dependency the way the member reads it: one
     * no feature of the member names is the member's own root; one a feature names rides only when
     * the member's default features activate it, and arrives as a plain root either way.
     */
    @Test
    void a_members_optional_dependency_joins_the_merge_as_the_member_reads_it() {
        JkBuild root = workspaceRoot("ws", List.of("plain", "gated", "on"));
        Dependency mysql = dep("mysql", "com.foo:mysql", "1.0").withOptional(true);
        JkBuild plain = newProject("plain", Map.of(Scope.MAIN, List.of(mysql)));
        Dependency pg = dep("pg", "com.foo:pg", "1.0").withOptional(true);
        JkBuild gated = JkBuild.builder(new Project("cc.jumpkick", "gated", "0.1.0", 0))
                .dependencies(new JkBuild.Dependencies(Map.of(Scope.MAIN, List.of(pg))))
                .features(new Features(Map.of("db", new Feature("db", List.of("pg"), List.of())), List.of()))
                .build();
        Dependency redis = dep("redis", "com.foo:redis", "1.0").withOptional(true);
        JkBuild on = JkBuild.builder(new Project("cc.jumpkick", "on", "0.1.0", 0))
                .dependencies(new JkBuild.Dependencies(Map.of(Scope.MAIN, List.of(redis))))
                .features(new Features(
                        Map.of("cache", new Feature("cache", List.of("redis"), List.of())), List.of("cache")))
                .build();

        JkBuild merged = WorkspaceMerge.merge(root, List.of(plain, gated, on));

        assertThat(merged.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module, Dependency::optional)
                .containsExactly(tuple("com.foo:mysql", false), tuple("com.foo:redis", false));
    }

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
