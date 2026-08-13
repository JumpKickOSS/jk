// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DependencyTreeTest {

    @Test
    void renders_simple_tree() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock =
                lockOf(pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")), pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).isEqualToIgnoringWhitespace("""
                com.example:widget:0.1.0
                ╰─ com.foo:root:1.0
                    ╰─ com.foo:leaf:1.0
                """);
    }

    @Test
    void marks_diamond_repeats_with_asterisk() {
        // root -> a -> leaf
        //      -> b -> leaf
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        // First occurrence: full label. Second: ⎋ marker.
        assertThat(rendered).contains("com.foo:leaf:1.0\n");
        assertThat(rendered).contains("com.foo:leaf:1.0 ⎋\n");
    }

    @Test
    void back_reference_rows_are_styled_as_a_whole_unit() {
        // root -> a -> leaf ; root -> b -> leaf  (leaf revisited under b)
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));
        var styling = new DependencyTree.Styling(
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                s -> "<dim>" + s + "</dim>", // reference styler
                UnaryOperator.identity()); // scope badge

        String rendered = DependencyTree.render(project, lock, Integer.MAX_VALUE, styling);

        // The revisited leaf is a back-reference: connector + coord + ⎋ wrapped as ONE unit.
        assertThat(rendered).contains("com.foo:leaf:1.0 ⎋</dim>");
        assertThat(rendered).contains("<dim>");
        // The first leaf occurrence is a real node — not wrapped by the reference styler.
        assertThat(rendered).contains("─ com.foo:leaf:1.0\n");
    }

    @Test
    void depth_zero_shows_only_roots() {
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock =
                lockOf(pkg("com.foo:root", "1.0", List.of("com.foo:leaf@1.0")), pkg("com.foo:leaf", "1.0", List.of()));

        String rendered = DependencyTree.render(project, lock, 0);
        assertThat(rendered).contains("com.foo:root:1.0");
        assertThat(rendered).doesNotContain("leaf");
    }

    @Test
    void missing_lockfile_entry_marked_as_missing() {
        JkBuild project = projectWithMainDeps("com.foo:not-locked");
        Lockfile lock = lockOf();

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).contains("com.foo:not-locked (missing)");
    }

    @Test
    void platform_bom_shows_declared_version_not_missing() {
        // BOM is pin metadata (pinned-by on managed jars), not a lock [[artifact]] row.
        var platform = List.of(Dependency.of(
                "boot", "org.springframework.boot:spring-boot-dependencies", VersionSelector.parse("=4.1.0")));
        var main = List.of(Dependency.of(
                "web", "org.springframework.boot:spring-boot-starter-webmvc", VersionSelector.parse("=4.1.0")));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.PLATFORM, platform, Scope.MAIN, main)));
        Lockfile lock = lockOf(pkg("org.springframework.boot:spring-boot-starter-webmvc", "4.1.0", List.of()));

        String rendered = DependencyTree.render(project, lock);
        assertThat(rendered).contains("org.springframework.boot:spring-boot-dependencies:4.1.0");
        assertThat(rendered).contains("(platform)");
        assertThat(rendered).doesNotContain("spring-boot-dependencies (missing)");
        assertThat(rendered).doesNotContain("spring-boot-dependencies" + DependencyTree.MISSING_SUFFIX);
        // Real missing main dep still marked
        JkBuild missingMain = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(
                        Scope.PLATFORM,
                        platform,
                        Scope.MAIN,
                        List.of(new Dependency("com.foo:absent", VersionSelector.parse("=1.0"))))));
        String bad = DependencyTree.render(missingMain, lockOf());
        assertThat(bad).contains("com.foo:absent (missing)");
        assertThat(bad).contains("spring-boot-dependencies:4.1.0");
    }

    @Test
    void branch_git_dep_renders_as_a_locked_coordinate(@org.junit.jupiter.api.io.TempDir Path tmp) {
        // A branch-ref git dep is materialized and pinned in jk-lock.toml like any other
        // git dep — it renders exactly like a locked Maven coordinate, no special tag.
        var deps = new ArrayList<Dependency>();
        deps.add(Dependency.git(
                "com.foo:forked",
                cc.jumpkick.model.GitSource.of(
                        "https://x/forked", "https://x/forked", new cc.jumpkick.model.GitRefSpec.Branch("main"))));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));
        Lockfile lock = lockOf(pkg("com.foo:forked", "main-SNAPSHOT", List.of()));

        String rendered = DependencyTree.render(project, lock, tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.foo:forked:main-SNAPSHOT");
        assertThat(rendered).doesNotContain("[git:");
        assertThat(rendered).doesNotContain("(missing)");
    }

    @Test
    void branch_git_dep_missing_from_lock_is_marked_missing(@org.junit.jupiter.api.io.TempDir Path tmp) {
        var deps = new ArrayList<Dependency>();
        deps.add(Dependency.git(
                "com.foo:forked",
                cc.jumpkick.model.GitSource.of(
                        "https://x/forked", "https://x/forked", new cc.jumpkick.model.GitRefSpec.Branch("main"))));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));

        String rendered =
                DependencyTree.render(project, lockOf(), tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.foo:forked (missing)");
    }

    @Test
    void direct_deps_are_grouped_into_scope_sections(@org.junit.jupiter.api.io.TempDir Path tmp) {
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(
                        Scope.MAIN, List.of(new Dependency("com.foo:lib", new VersionSelector.Exact("=1.0", "1.0"))),
                        Scope.TEST,
                                List.of(new Dependency("org.junit:junit", new VersionSelector.Exact("=5.0", "5.0"))))));
        Lockfile lock = lockOf(pkg("com.foo:lib", "1.0", List.of()), pkg("org.junit:junit", "5.0", List.of()));

        // Default scopes are export/main/runtime — pass test explicitly for this grouping check.
        String rendered = DependencyTree.render(
                project,
                lock,
                tmp,
                Integer.MAX_VALUE,
                DependencyTree.Styling.plain(),
                false,
                List.of(Scope.MAIN, Scope.TEST));

        // main + test sections present; empty scopes (provided, runtime, …) omitted.
        // (Plain styling emits the bare scope label; padding/caps are the styler's job.)
        assertThat(rendered).contains("main").contains("test");
        assertThat(rendered).doesNotContain("provided").doesNotContain("runtime");
        // Each dep lands under its own scope; main before test.
        assertThat(rendered.indexOf("main")).isLessThan(rendered.indexOf("com.foo:lib"));
        assertThat(rendered.indexOf("com.foo:lib")).isLessThan(rendered.indexOf("test"));
        assertThat(rendered.indexOf("test")).isLessThan(rendered.indexOf("org.junit:junit"));
    }

    @Test
    void default_scopes_are_export_main_runtime_only(@org.junit.jupiter.api.io.TempDir Path tmp) {
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(
                        Scope.MAIN, List.of(new Dependency("com.foo:lib", new VersionSelector.Exact("=1.0", "1.0"))),
                        Scope.TEST,
                                List.of(new Dependency("org.junit:junit", new VersionSelector.Exact("=5.0", "5.0"))))));
        Lockfile lock = lockOf(pkg("com.foo:lib", "1.0", List.of()), pkg("org.junit:junit", "5.0", List.of()));

        String rendered = DependencyTree.render(project, lock, tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("main").contains("com.foo:lib");
        assertThat(rendered).doesNotContain("test").doesNotContain("org.junit:junit");
        assertThat(DependencyTree.defaultScopeOrder()).containsExactly(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
    }

    /**
     * JK-1640: a test-only project under the narrowed default (export/main/runtime) must not print
     * an effectively empty tree — it names the scopes that DO have deps and how to show them.
     */
    @Test
    void empty_selection_names_the_scopes_that_do_have_deps(@org.junit.jupiter.api.io.TempDir Path tmp) {
        JkBuild testOnly = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(
                        Scope.TEST,
                        List.of(new Dependency("org.junit:junit", new VersionSelector.Exact("=5.0", "5.0"))))));
        Lockfile lock = lockOf(pkg("org.junit:junit", "5.0", List.of()));

        String rendered = DependencyTree.render(testOnly, lock, tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("found in: test").contains("-s test");

        // Two populated scopes → steer to `-s all` instead of listing one flag per scope.
        JkBuild testAndDev = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(
                        Scope.TEST,
                                List.of(new Dependency("org.junit:junit", new VersionSelector.Exact("=5.0", "5.0"))),
                        Scope.DEV, List.of(new Dependency("com.foo:tool", new VersionSelector.Exact("=1.0", "1.0"))))));
        String two = DependencyTree.render(testAndDev, lock, tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());
        assertThat(two).contains("found in: test, dev").contains("-s all");

        // No deps anywhere → plain "(no dependencies)", no bogus steer.
        JkBuild none = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0), new JkBuild.Dependencies(Map.of()));
        String empty = DependencyTree.render(none, lockOf(), tmp, Integer.MAX_VALUE, DependencyTree.Styling.plain());
        assertThat(empty).contains("(no dependencies)").doesNotContain("found in:");
    }

    @Test
    void workspace_root_groups_modules_under_scope_sections(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        // A workspace root with two modules; module b depends on a via `workspace = true`.
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "ws"
                version = "9.9.9"

                [workspace]
                modules = ["a", "b"]
                """);
        Path a = Files.createDirectories(root.resolve("a"));
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "a"
                version = "9.9.9"
                """);
        Files.writeString(a.resolve("jk-lock.toml"), EMPTY_LOCK);
        Path b = Files.createDirectories(root.resolve("b"));
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "b"
                version = "9.9.9"

                [dependencies]
                a = { workspace = true }
                """);
        Files.writeString(b.resolve("jk-lock.toml"), EMPTY_LOCK);

        JkBuild rootProject = cc.jumpkick.config.JkBuildParser.parse(root.resolve("jk.toml"));
        String rendered =
                DependencyTree.render(rootProject, lockOf(), root, Integer.MAX_VALUE, DependencyTree.Styling.plain());

        assertThat(rendered).contains("com.acme:ws:9.9.9"); // root
        // Scope-first: a `main` section is the top-level node, and module b (the only
        // module that declares a main dep) is a node beneath it. Module a has no deps
        // of its own, so it is NOT a standalone node — it appears only as b's sibling.
        assertThat(rendered).contains("main");
        assertThat(rendered).contains("com.acme:b:9.9.9");
        // b's `workspace = true` dep on a: collapsed reference, resolved to a's real
        // coord (not the synthetic "workspace:a") and not flagged "(missing)".
        assertThat(rendered).contains("com.acme:a [workspace]");
        assertThat(rendered).doesNotContain("workspace:a");
        assertThat(rendered).doesNotContain("(missing)");
        // b is the group node; a appears only as b's collapsed sibling nested under it.
        assertThat(rendered.indexOf("com.acme:b:9.9.9")).isLessThan(rendered.indexOf("com.acme:a [workspace]"));
    }

    @Test
    void member_tree_resolves_workspace_siblings_and_expands_when_depth_allows(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        // b → a (workspace) → c (workspace) + leaf → grand. Member-scoped trees must
        // treat siblings as modules (version from jk.toml), not lock-missing Maven coords.
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name = "ws"
                version = "9.9.9"

                [workspace]
                modules = ["a", "b", "c"]
                """);
        Files.writeString(root.resolve("jk-lock.toml"), EMPTY_LOCK);
        Path a = Files.createDirectories(root.resolve("a"));
        Files.writeString(a.resolve("jk.toml"), """
                [project]
                name = "a"

                [dependencies]
                c = { workspace = true }
                leaf = { group = "com.foo", name = "leaf", version = "1.0" }
                """);
        Path b = Files.createDirectories(root.resolve("b"));
        Files.writeString(b.resolve("jk.toml"), """
                [project]
                name = "b"

                [dependencies]
                a = { workspace = true }
                """);
        Path c = Files.createDirectories(root.resolve("c"));
        Files.writeString(c.resolve("jk.toml"), """
                [project]
                name = "c"
                """);
        Lockfile lock = lockOf(
                pkg("com.foo:leaf", "1.0", List.of("com.foo:grand@1.0")), pkg("com.foo:grand", "1.0", List.of()));

        JkBuild member = cc.jumpkick.config.JkBuildParser.parse(b.resolve("jk.toml"));
        String declared = DependencyTree.render(member, lock, b, 0, DependencyTree.Styling.plain());
        assertThat(declared).contains("com.acme:b:9.9.9");
        assertThat(declared).contains("com.acme:a:9.9.9");
        assertThat(declared).doesNotContain("(missing)");
        assertThat(declared).doesNotContain("com.acme:c");
        assertThat(declared).doesNotContain("com.foo:leaf");
        assertThat(declared).doesNotContain("com.foo:grand");

        String transitive = DependencyTree.render(member, lock, b, Integer.MAX_VALUE, DependencyTree.Styling.plain());
        assertThat(transitive).contains("com.acme:a:9.9.9");
        assertThat(transitive).contains("com.acme:c:9.9.9");
        assertThat(transitive).contains("com.foo:leaf:1.0");
        assertThat(transitive).contains("com.foo:grand:1.0");
        assertThat(transitive).doesNotContain("(missing)");
        assertThat(transitive).doesNotContain("[workspace]");

        String flat = DependencyTree.render(member, lock, b, Integer.MAX_VALUE, DependencyTree.Styling.plain(), true);
        assertThat(flat)
                .contains("com.acme:a:9.9.9")
                .contains("com.acme:c:9.9.9")
                .contains("com.foo:leaf:1.0")
                .contains("com.foo:grand:1.0");
        assertThat(flat).doesNotContain("(missing)").doesNotContain("[workspace]");
    }

    @Test
    void flatten_lists_each_scope_dep_once_without_nesting(@org.junit.jupiter.api.io.TempDir Path dir) {
        // Diamond: root -> a -> leaf ; root -> b -> leaf.
        JkBuild project = projectWithMainDeps("com.foo:root");
        Lockfile lock = lockOf(
                pkg("com.foo:root", "1.0", List.of("com.foo:a@1.0", "com.foo:b@1.0")),
                pkg("com.foo:a", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:b", "1.0", List.of("com.foo:leaf@1.0")),
                pkg("com.foo:leaf", "1.0", List.of()));

        String rendered =
                DependencyTree.render(project, lock, dir, Integer.MAX_VALUE, DependencyTree.Styling.plain(), true);

        // Whole closure present, flat, with no back-reference markers.
        assertThat(rendered)
                .contains("com.foo:root:1.0")
                .contains("com.foo:a:1.0")
                .contains("com.foo:b:1.0")
                .contains("com.foo:leaf:1.0");
        assertThat(rendered).doesNotContain("⎋");
        // leaf is deduped to a single line despite two paths to it.
        int first = rendered.indexOf("com.foo:leaf:1.0");
        assertThat(rendered.indexOf("com.foo:leaf:1.0", first + 1)).isEqualTo(-1);
    }

    @Test
    void explicit_scope_order_filters_and_reorders_sections(@org.junit.jupiter.api.io.TempDir Path dir) {
        var main = List.of(new Dependency("com.foo:m", new VersionSelector.Exact("=1.0", "1.0")));
        var test = List.of(new Dependency("com.foo:t", new VersionSelector.Exact("=1.0", "1.0")));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, main, Scope.TEST, test)));
        Lockfile lock = lockOf(pkg("com.foo:m", "1.0", List.of()), pkg("com.foo:t", "1.0", List.of()));

        String rendered = DependencyTree.render(
                project,
                lock,
                dir,
                Integer.MAX_VALUE,
                DependencyTree.Styling.plain(),
                false,
                List.of(Scope.TEST, Scope.MAIN));

        // Both requested sections render, in the given order (test before main),
        // overriding the default ordering; unrequested scopes are omitted.
        assertThat(rendered).contains("test").contains("main").doesNotContain("provided");
        assertThat(rendered.indexOf("test")).isLessThan(rendered.indexOf("main"));
    }

    @Test
    void stack_blends_all_scopes_under_one_badge_row(@org.junit.jupiter.api.io.TempDir Path dir) {
        var main = List.of(new Dependency("com.foo:m", new VersionSelector.Exact("=1.0", "1.0")));
        var test = List.of(new Dependency("com.foo:t", new VersionSelector.Exact("=1.0", "1.0")));
        JkBuild project = new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, main, Scope.TEST, test)));
        Lockfile lock = lockOf(pkg("com.foo:m", "1.0", List.of()), pkg("com.foo:t", "1.0", List.of()));

        String rendered = DependencyTree.render(
                project,
                lock,
                dir,
                Integer.MAX_VALUE,
                DependencyTree.Styling.plain(),
                false,
                List.of(Scope.MAIN, Scope.TEST),
                true);

        // A single header line carries every scope badge; deps from all scopes are
        // blended into the one tree beneath it.
        List<String> headers = Arrays.stream(rendered.split("\n"))
                .filter(l -> l.contains("main"))
                .toList();
        assertThat(headers).hasSize(1);
        assertThat(headers.get(0)).contains("main").contains("test");
        assertThat(rendered).contains("com.foo:m:1.0").contains("com.foo:t:1.0");
    }

    // --- helpers -----------------------------------------------------------

    private static final String EMPTY_LOCK = """
            version = 1
            generated-by = "jk test"
            resolution-algorithm = "pubgrub-v1"
            """;

    private static JkBuild projectWithMainDeps(String... modules) {
        var deps = new ArrayList<Dependency>();
        for (String m : modules) {
            deps.add(new Dependency(m, new VersionSelector.Exact("=1.0", "1.0")));
        }
        return new JkBuild(
                new JkBuild.Project("com.example", "widget", "0.1.0", 0),
                new JkBuild.Dependencies(Map.of(Scope.MAIN, deps)));
    }

    private static Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Artifact pkg(String module, String version, List<String> deps) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", "sha256:dummy", null, deps);
    }
}
