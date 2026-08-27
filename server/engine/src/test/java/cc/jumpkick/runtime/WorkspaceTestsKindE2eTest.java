// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: Mill {@code testModuleDeps} / Maven test-jar via {@code kind = "tests"}.
 *
 * <p>Lib ships a test helper under {@code src/test}; app's test scope selects the sibling's tests
 * kind. After building lib (with tests), app's test classpath must include lib's test classes,
 * and app's test that references the helper must pass.
 */
// Out of the unit tier: network resolve + a real forked test JVM.
@Tag("integration")
class WorkspaceTestsKindE2eTest {

    @Test
    void app_tests_can_use_sibling_test_helpers_via_kind_tests(@TempDir Path tmp) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));

        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);

        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), """
                package com.example;
                public final class Lib {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        Files.createDirectories(lib.resolve("test/src/com/example"));
        Files.writeString(lib.resolve("test/src/com/example/LibTestHelper.java"), """
                package com.example;
                /** Shared test helper — only visible via kind=tests. */
                public final class LibTestHelper {
                    public static int expectedTwice(int n) { return Lib.twice(n); }
                }
                """);
        Files.writeString(lib.resolve("test/src/com/example/LibTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class LibTest {
                    @Test void twice() { assertEquals(4, LibTestHelper.expectedTwice(2)); }
                }
                """);

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                lib = { workspace = true, kind = "tests" }
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int useLib(int n) { return Lib.twice(n); }
                }
                """);
        Files.createDirectories(app.resolve("test/src/com/example"));
        Files.writeString(app.resolve("test/src/com/example/AppTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class AppTest {
                    @Test
                    void uses_sibling_test_helper() {
                        // LibTestHelper lives only in lib's test output — kind=tests is required.
                        assertEquals(LibTestHelper.expectedTwice(3), App.useLib(3));
                    }
                }
                """);

        // Parse surface: kind on the test edge, not on main.
        JkBuild appManifest = JkBuildParser.parse(app.resolve("jk.toml"));
        assertThat(appManifest.dependencies().of(Scope.MAIN)).allMatch(d -> !d.isTestsKind());
        assertThat(appManifest.dependencies().of(Scope.TEST)).anyMatch(d -> d.isTestsKind());

        // Lock at workspace root (union).
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        // Members redirect to the root lock.
        Files.copy(ws.resolve("jk-lock.toml"), lib.resolve("jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), app.resolve("jk-lock.toml"));

        // Build lib first (incl. tests) so classes/test exists for the kind=tests edge.
        assertThat(build(lib, cache).success()).as("lib build+test").isTrue();
        Path libTestClasses = ws.resolve("target/lib/classes/test");
        assertThat(libTestClasses).isDirectory();
        assertThat(Files.walk(libTestClasses)
                        .anyMatch(p -> p.getFileName().toString().endsWith("LibTestHelper.class")))
                .as("lib test helper compiled")
                .isTrue();

        // Classpath contract before app tests run.
        var testCp = WorkspaceClasspath.resolve(app, appManifest, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
        assertThat(testCp.jars()).anyMatch(p -> p.endsWith(Path.of("classes/test")));
        assertThat(testCp.missingSiblingJars()).isEmpty();

        var mainCp = WorkspaceClasspath.resolve(app, appManifest, Set.of(Scope.EXPORT, Scope.MAIN));
        assertThat(mainCp.jars().stream().map(Object::toString).toList()).noneMatch(p -> p.contains("classes/test"));

        // App tests must pass only if kind=tests put the helper on the test CP.
        assertThat(build(app, cache).success())
                .as("app build+test uses sibling kind=tests helpers")
                .isTrue();
    }

    private static BuildPlanResult build(Path module, Path cache) {
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                module,
                cache,
                module.resolve("jk.toml"),
                module.resolve("jk-lock.toml"),
                module,
                1,
                1,
                null,
                null,
                /* skipTests */ false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        // Core + tails, like jk build: post-JK-2211 run-tests (and its compile-test) are on
        // the terminal-join branch, not the packaging path — a core-only plan prunes them.
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        PlannerTails.appendDeclaredTails(b, in);
        return b.build().run();
    }
}
