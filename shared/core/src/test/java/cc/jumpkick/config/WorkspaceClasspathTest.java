// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** lib ←(export)— app ←(main)— top */
    /**
     * A member listed in {@code [workspace] modules} with no {@code jk.toml} is fatal for a module
     * that has workspace dependencies — and it was already fatal before the sibling index changed.
     *
     * <p>Pinned while doing JK-1046, because the risk I set out to protect against turned out not to
     * exist. The worry was that building the index from {@code WorkspaceLoader.loadModules} — which
     * throws on a missing member — would break a tolerance that {@code resolve}'s own
     * {@code if (!Files.exists(manifest)) continue} appeared to provide. It provides nothing here:
     * {@code JkBuildParser.parse} on the <em>consumer</em> already runs {@code applyWorkspace}, which
     * calls {@code loadModules} and rethrows for any module carrying workspace deps. The caller never
     * reaches {@code resolve} with a broken workspace.
     *
     * <p>So the substitution cannot change this, and this test is here to say so if anyone widens the
     * tolerance later and expects the classpath to follow.
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
     * The transitive closure is unaffected by platform contributions.
     *
     * <p>Also pinned for JK-1046: the sibling index used to come from a full {@code parse} (which
     * re-applies platform contributions) and now comes from {@code loadModules} (which does not). The
     * BFS only follows dependencies that resolve to a workspace sibling, and contributions add
     * external coordinates, so it should not care — this asserts that rather than assuming it.
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
        org.junit.jupiter.api.Assertions.assertThrows(JkBuildParseException.class, () -> JkBuildParser.parse(appToml));
    }

    private static List<String> jarNames(WorkspaceClasspath.Result result) {
        return result.siblingClosureJars().stream()
                .map(p -> p.getFileName().toString())
                .toList();
    }
}
