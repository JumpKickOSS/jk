// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace-internal classpath resolution, focused on {@code [export-dependencies]} acting like a
 * main dependency that also rides transitively to consumers.
 */
class WorkspaceClasspathTest {

    @Test
    void export_sibling_is_on_the_declaring_modules_own_classpath(@TempDir Path root) throws Exception {
        scaffold(root);
        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));
        // `app` declares `lib` in [export-dependencies]; it must be on app's own classpath.
        assertThat(jarNames(result)).anyMatch(n -> n.startsWith("lib-"));
    }

    @Test
    void export_deps_ride_transitively_to_a_consumer(@TempDir Path root) throws Exception {
        scaffold(root);
        JkBuild top = JkBuildParser.parse(root.resolve("top/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("top"), top, Set.of(Scope.EXPORT, Scope.MAIN));
        // `top` depends on `app` (main); `app` exports `lib` — so `top` transitively
        // gets BOTH app and lib (the export rider).
        List<String> names = jarNames(result);
        assertThat(names).anyMatch(n -> n.startsWith("app-"));
        assertThat(names).anyMatch(n -> n.startsWith("lib-"));
    }

    /**
     * A sibling's {@code optional = true} edge onto another module is the sibling's own: it is on
     * the sibling's classpath and never chains to the sibling's consumers.
     */
    @Test
    void a_siblings_optional_module_edge_does_not_chain_to_its_consumer(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app", "top"]
                """);
        module(root, "lib", "");
        module(root, "app", """
                [dependencies]
                lib = { workspace = true, optional = true }
                """);
        module(root, "top", """
                [dependencies]
                app = { workspace = true }
                """);
        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        JkBuild top = JkBuildParser.parse(root.resolve("top/jk.toml"));

        List<String> own = jarNames(WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.MAIN)));
        List<String> consumer = jarNames(WorkspaceClasspath.resolve(root.resolve("top"), top, Set.of(Scope.MAIN)));

        assertThat(own).anyMatch(n -> n.startsWith("lib-"));
        assertThat(consumer).anyMatch(n -> n.startsWith("app-")).noneMatch(n -> n.startsWith("lib-"));
    }

    /**
     * The closure a consumer reads siblings' sources through is the classpath closure, as
     * directories and manifests: {@code top} depends on {@code app}, which exports {@code lib}, so
     * both are its siblings in that order, and {@code lib} — depending on nothing — has none.
     */
    @Test
    void closure_siblings_are_the_transitive_dependency_siblings_with_their_manifests(@TempDir Path root)
            throws Exception {
        scaffold(root);
        JkBuild top = JkBuildParser.parse(root.resolve("top/jk.toml"));
        JkBuild lib = JkBuildParser.parse(root.resolve("lib/jk.toml"));

        var siblings = WorkspaceClasspath.closureSiblings(root.resolve("top"), top, Set.of(Scope.EXPORT, Scope.MAIN));

        assertThat(siblings.keySet()).containsExactly(root.resolve("app"), root.resolve("lib"));
        assertThat(siblings.values()).extracting(m -> m.project().name()).containsExactly("app", "lib");
        assertThat(WorkspaceClasspath.closureSiblings(root.resolve("lib"), lib, Set.of(Scope.MAIN)))
                .isEmpty();
    }

    /** lib ←(export)— app ←(main)— top */
    /**
     * A member listed in {@code [workspace] modules} with no {@code jk.toml} is fatal for a module
     * that has workspace dependencies. {@code JkBuildParser.parse} on the consumer runs
     * {@code applyWorkspace} → {@code loadModules} and fails before classpath resolve.
     */
    @Test
    void a_member_with_no_manifest_is_fatal_for_a_module_with_workspace_deps(@TempDir Path root) throws Exception {
        scaffold(root);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app", "top", "not-created-yet"]
                """);

        assertThatThrownBy(() -> JkBuildParser.parse(root.resolve("app/jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("not-created-yet");
    }

    /**
     * The transitive sibling closure is unaffected by platform contributions.
     * Contributions add external coordinates; the BFS only follows workspace siblings.
     */
    @Test
    void the_sibling_closure_is_transitive_through_a_kotlin_module(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["core", "mid", "app"]
                """);
        module(root, "core", "");
        // A Kotlin module: platform contributions apply to it under a full parse and not under
        // loadModules, so it is the interesting middle of the chain.
        Path mid = Files.createDirectories(root.resolve("mid"));
        Files.writeString(mid.resolve("jk.toml"), """
                group = "com.ex"
                name = "mid"
                version = "0.1.0"
                jdk = "25"
                kotlin = "2.0.21"

                [export-dependencies]
                core = { workspace = true }
                """);
        module(root, "app", """
                [dependencies]
                mid = { workspace = true }
                """);

        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.MAIN));

        // core rides through mid's export edge — the transitive hop the index has to preserve.
        assertThat(jarNames(result)).anyMatch(n -> n.startsWith("core-"));
        assertThat(jarNames(result)).anyMatch(n -> n.startsWith("mid-"));
    }

    private static void scaffold(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app", "top"]
                """);
        module(root, "lib", "");
        module(root, "app", """
                [export-dependencies]
                lib = { workspace = true }
                """);
        module(root, "top", """
                [dependencies]
                app = { workspace = true }
                """);
    }

    private static void module(Path root, String name, String depsBlock) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "0.1.0"
                jdk = "25"

                %s""".formatted(name, depsBlock));
    }

    @Test
    void tests_kind_puts_sibling_test_classes_on_the_test_classpath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", "");
        module(root, "app", """
                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                lib = { workspace = true, kind = "tests" }
                """);
        // Materialize the products WorkspaceClasspath looks for.
        Path libMain = root.resolve("target/lib/lib/lib-0.1.0.jar");
        Path libTestClasses = root.resolve("target/lib/classes/test");
        Files.createDirectories(libMain.getParent());
        Files.createDirectories(libTestClasses);
        Files.writeString(libMain, "jar");
        Files.writeString(libTestClasses.resolve("Helper.class"), "class");

        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var mainOnly = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));
        assertThat(mainOnly.jars().stream().map(Object::toString).toList()).noneMatch(p -> p.contains("classes/test"));

        var withTests =
                WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
        assertThat(withTests.jars()).anyMatch(p -> p.endsWith(Path.of("classes/test")));
        assertThat(withTests.missingSiblingJars()).isEmpty();
    }

    @Test
    void kind_tests_outside_test_scope_is_rejected(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", "");
        Path appToml = root.resolve("app/jk.toml");
        Files.createDirectories(appToml.getParent());
        Files.writeString(appToml, """
                group = "com.ex"
                name = "app"
                version = "0.1.0"
                jdk = "25"

                [dependencies]
                lib = { workspace = true, kind = "tests" }
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(appToml)).isInstanceOf(JkBuildParseException.class);
    }

    /** Only a tests kind or fixtures = true reads a sibling's test stage; a plain edge reads its classes tree. */
    @Test
    void a_manifest_selects_sibling_test_outputs_only_through_a_tests_kind_or_fixtures(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "plain", "tests", "fixtures"]
                """);
        module(root, "lib", """
                [test]
                fixtures = true
                """);
        module(root, "plain", """
                [dependencies]
                lib = { workspace = true }
                """);
        module(root, "tests", """
                [test-dependencies]
                lib = { workspace = true, kind = "tests" }
                """);
        module(root, "fixtures", """
                [test-dependencies]
                lib = { workspace = true, fixtures = true }
                """);

        assertThat(WorkspaceClasspath.selectsTestOutputs(JkBuildParser.parse(root.resolve("plain/jk.toml"))))
                .as("a main edge compiles against lib's classes tree")
                .isFalse();
        assertThat(WorkspaceClasspath.selectsTestOutputs(JkBuildParser.parse(root.resolve("tests/jk.toml"))))
                .as("a tests kind reads lib's classes/test")
                .isTrue();
        assertThat(WorkspaceClasspath.selectsTestOutputs(JkBuildParser.parse(root.resolve("fixtures/jk.toml"))))
                .as("fixtures = true reads lib's fixtures")
                .isTrue();
    }

    @Test
    void fixtures_puts_sibling_fixtures_dir_on_the_test_classpath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", """
                [test]
                fixtures = true
                """);
        module(root, "app", """
                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                lib = { workspace = true, fixtures = true }
                """);
        Path libMain = root.resolve("target/lib/lib/lib-0.1.0.jar");
        Path libFixtures = root.resolve("target/lib/test-fixtures/classes");
        Files.createDirectories(libMain.getParent());
        Files.createDirectories(libFixtures);
        Files.writeString(libMain, "jar");
        Files.writeString(libFixtures.resolve("Helper.class"), "class");

        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var mainOnly = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));
        assertThat(mainOnly.jars().stream().map(Object::toString).toList()).noneMatch(p -> p.contains("test-fixtures"));

        var withFixtures =
                WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
        assertThat(withFixtures.jars()).anyMatch(p -> p.endsWith(Path.of("test-fixtures/classes")));
        assertThat(withFixtures.missingSiblingJars()).isEmpty();
    }

    @Test
    void fixtures_outside_test_scope_is_rejected(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", "");
        Path appToml = root.resolve("app/jk.toml");
        Files.createDirectories(appToml.getParent());
        Files.writeString(appToml, """
                group = "com.ex"
                name = "app"
                version = "0.1.0"
                jdk = "25"

                [dependencies]
                lib = { workspace = true, fixtures = true }
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(appToml)).isInstanceOf(JkBuildParseException.class);
    }

    /**
     * The compile view is the runtime view with every sibling's jar replaced by its classes tree:
     * same siblings, same order, and the tests-kind / fixtures directories shared between them.
     */
    @Test
    void the_compile_view_names_sibling_classes_trees_where_the_runtime_view_names_jars(@TempDir Path root)
            throws Exception {
        scaffold(root);
        JkBuild top = JkBuildParser.parse(root.resolve("top/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("top"), top, Set.of(Scope.EXPORT, Scope.MAIN));

        assertThat(result.siblingClosureClasses()).hasSameSizeAs(result.siblingClosureJars());
        assertThat(result.siblingClosureClasses())
                .allMatch(p -> p.endsWith(Path.of("classes", "main")))
                .anyMatch(p -> p.toString().contains("app"))
                .anyMatch(p -> p.toString().contains("lib"));
        assertThat(result.siblingClosureJars())
                .allMatch(p -> p.getFileName().toString().endsWith(".jar"));
        // Nothing is built: both views are declared, and each names every sibling's own absence.
        assertThat(result.missingSiblingClasses()).hasSize(2).allMatch(m -> m.replace('\\', '/')
                .endsWith("classes/main"));
        assertThat(result.missingSiblingJars()).hasSize(2).allMatch(m -> m.endsWith(".jar"));
    }

    /**
     * A sibling that has compiled but not yet packaged is whole for a consumer's compile and
     * still missing for anything that runs it — the two absences are reported apart so a compile
     * can start on the tree while the jar is still being written.
     */
    @Test
    void a_compiled_but_unpackaged_sibling_is_missing_only_in_the_runtime_view(@TempDir Path root) throws Exception {
        scaffold(root);
        Files.createDirectories(root.resolve("lib/src/com/ex"));
        Files.writeString(root.resolve("lib/src/com/ex/Lib.java"), "package com.ex; public class Lib {}");
        Path libClasses = BuildLayout.of(root.resolve("lib"), JkBuildParser.parse(root.resolve("lib/jk.toml")))
                .classesDir();
        Files.createDirectories(libClasses.resolve("com/ex"));
        Files.writeString(libClasses.resolve("com/ex/Lib.class"), "bytes");

        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));

        assertThat(result.missingSiblingClasses()).isEmpty();
        assertThat(result.missingSiblingJars()).singleElement().asString().startsWith("com.ex:lib (expected at");
        assertThat(result.jars())
                .as("the runtime view lists only what is on disk")
                .isEmpty();
        assertThat(result.siblingClosureClasses()).containsExactly(libClasses);
    }

    /**
     * A sibling whose fat jar relocates packages is that jar in both views: the shaded names exist
     * nowhere else, so a consumer compiles against the {@code -all.jar}, waits for it as it waits
     * for classes, and does not take the sibling's own lock rows — the jar carries them.
     */
    @Test
    void a_relocating_sibling_is_its_shaded_jar_in_both_views_and_brings_no_lock(@TempDir Path root) throws Exception {
        scaffold(root);
        Files.writeString(root.resolve("lib/jk.toml"), """
                group = "com.ex"
                name = "lib"
                version = "0.1.0"
                jdk = "25"

                [library]
                relocate = { "com.ex.lib" = "com.ex.shaded.lib" }
                """);
        Files.writeString(root.resolve("lib/jk-lock.toml"), "version = 1\n");
        Files.createDirectories(root.resolve("lib/src/com/ex/lib"));
        Files.writeString(root.resolve("lib/src/com/ex/lib/Util.java"), "package com.ex.lib; public class Util {}");
        BuildLayout lib = BuildLayout.of(root.resolve("lib"), JkBuildParser.parse(root.resolve("lib/jk.toml")));
        Files.createDirectories(lib.classesDir().resolve("com/ex/lib"));
        Files.writeString(lib.classesDir().resolve("com/ex/lib/Util.class"), "bytes");

        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var before = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));
        assertThat(before.siblingClosureClasses()).containsExactly(lib.assemblyJar());
        assertThat(before.siblingClosureJars()).containsExactly(lib.assemblyJar());
        assertThat(before.missingSiblingClasses())
                .as("a classes tree is not what this consumer compiles against")
                .singleElement()
                .asString()
                .contains("expected its relocating jar at")
                .contains("lib-0.1.0-all.jar");
        assertThat(before.siblingLocks())
                .as("the jar bundles the sibling's dependencies")
                .isEmpty();

        Files.createDirectories(requireNonNull(lib.assemblyJar().getParent()));
        Files.writeString(lib.assemblyJar(), "jar");
        var after = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));
        assertThat(after.missingSiblingClasses()).isEmpty();
        assertThat(after.missingSiblingJars()).isEmpty();
        assertThat(after.jars()).containsExactly(lib.assemblyJar());
    }

    /** A sibling with nothing to compile names that cause in both views rather than a bare path. */
    @Test
    void a_sourceless_sibling_names_its_cause_in_both_views(@TempDir Path root) throws Exception {
        scaffold(root);
        JkBuild app = JkBuildParser.parse(root.resolve("app/jk.toml"));
        var result = WorkspaceClasspath.resolve(root.resolve("app"), app, Set.of(Scope.EXPORT, Scope.MAIN));

        assertThat(result.missingSiblingClasses()).singleElement().asString().contains("has no sources");
        assertThat(result.missingSiblingJars()).singleElement().asString().contains("has no sources");
    }

    private static List<String> jarNames(WorkspaceClasspath.Result result) {
        return result.siblingClosureJars().stream()
                .map(p -> p.getFileName().toString())
                .toList();
    }

    /**
     * A {@code [provided-dependencies]} sibling is what Maven's {@code provided} scope is: on the
     * consumer's compile and test classpaths, absent from what it packages and runs.
     */
    @Test
    void a_provided_sibling_is_in_the_compile_and_test_views_and_out_of_the_runtime_view(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                jdk = "25"

                [workspace]
                modules = ["api", "plugin"]
                """);
        module(root, "api", "");
        module(root, "plugin", """
                [provided-dependencies]
                api = { workspace = true }
                """);
        JkBuild plugin = JkBuildParser.parse(root.resolve("plugin/jk.toml"));

        var compile = WorkspaceClasspath.resolve(root.resolve("plugin"), plugin, WorkspaceClasspath.COMPILE_SCOPES);
        var test = WorkspaceClasspath.resolve(root.resolve("plugin"), plugin, WorkspaceClasspath.TEST_SCOPES);
        var runtime = WorkspaceClasspath.resolve(root.resolve("plugin"), plugin, WorkspaceClasspath.RUNTIME_SCOPES);

        assertThat(compile.siblingClosureClasses())
                .as("javac sees the provided sibling's classes tree")
                .singleElement()
                .satisfies(p -> assertThat(p.toString()).contains("api"));
        assertThat(test.siblingClosureClasses()).hasSize(1);
        assertThat(runtime.siblingClosureJars())
                .as("the jar never rides into the consumer's package or run")
                .isEmpty();
    }
}
