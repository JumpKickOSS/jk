// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a sibling's fixtures come back for the module that reads them.
 *
 * <p>lib declares {@code [test] fixtures = true}; app takes them with {@code fixtures = true} on
 * its test edge. A tests-enabled build produces both test views. With {@code target/} wiped, a
 * {@code jk install --skip-tests} restores the main outputs and, by design, no test view. The next
 * tests-enabled build then finds every record hitting and lib's main outputs current — and must
 * still schedule lib, so its fixtures tree is restored before app's test compile is admitted
 * against it, and app, so its own tests are compiled. Through the real preflight, planner, action
 * cache and workspace scheduler.
 *
 * <p>The skip-tests pass is an install, as in the sequence a fresh checkout of jk's own tree goes
 * through: an install target records no output provenance, so nothing but the test view itself
 * can put the module on the schedule. A package-target skip-tests build records its fingerprints
 * and the drift they show would schedule the module for another reason.
 *
 * <p>Network: the junit pins come from Maven Central into the cache under {@code build/}, which
 * persists across runs so repeats are warm.
 */
@Tag("integration")
class WorkspaceFixturesRestoreE2eTest {

    @Test
    void a_tests_enabled_build_after_a_skip_tests_build_restores_the_fixtures_a_sibling_reads(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("fixtures-restore-cache");
        Path ws = fixturesWorkspace(tmp);
        lock(ws, cache);
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));
        Path libFixture = lib.testFixturesClassesDir().resolve("com/example/LibFixture.class");
        Path appTest = app.testClassesDir().resolve("com/example/AppTest.class");

        build(ws, cache, false, WorkspaceSpec.DEFAULT, "the first build, tests enabled");
        assertThat(libFixture).as("lib's fixtures compile").isRegularFile();
        assertThat(appTest).as("app's tests compile against them").isRegularFile();

        PathUtil.deleteRecursively(ws.resolve("target"));
        WorkspaceSpec install = WorkspaceSpec.install(Set.of(), Map.of(), tmp.resolve("m2"));
        build(ws, cache, true, install, "the install --skip-tests on an empty target/");
        assertThat(lib.mainJar()).as("main outputs come back").isRegularFile();
        assertThat(libFixture).as("--skip-tests produces no test view").doesNotExist();
        assertThat(appTest).doesNotExist();

        build(ws, cache, false, WorkspaceSpec.DEFAULT, "the tests-enabled build after it");
        assertThat(libFixture)
                .as("lib is scheduled on its absent test view alone and restores its fixtures")
                .isRegularFile();
        assertThat(appTest)
                .as("app's tests are compiled against the restored fixtures")
                .isRegularFile();
    }

    private static void build(Path ws, Path cache, boolean skipTests, WorkspaceSpec spec, String label) {
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(
                        ws, cache, null, 0, null, skipTests, false, 2, null, false, false, false, "", Map.of(), false,
                        spec, List.of(), false),
                new WorkspaceBuildListener() {});
        assertThat(result.errors()).as(label).isEmpty();
        assertThat(result.success()).as(label).isTrue();
    }

    private static void lock(Path ws, Path cache) throws IOException {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("lib/jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
    }

    /** lib publishes a fixture only its fixtures tree carries; app selects it with {@code fixtures = true}. */
    private static Path fixturesWorkspace(Path tmp) throws IOException {
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

                [test]
                fixtures = true

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
        Files.createDirectories(lib.resolve("src/fixtures/java/com/example"));
        Files.writeString(lib.resolve("src/fixtures/java/com/example/LibFixture.java"), """
                package com.example;
                /** A shared test primitive: only the fixtures tree carries it. */
                public final class LibFixture {
                    public static int expectedTwice(int n) { return Lib.twice(n); }
                }
                """);
        Files.createDirectories(lib.resolve("test/src/com/example"));
        Files.writeString(lib.resolve("test/src/com/example/LibTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class LibTest {
                    @Test void twice() { assertEquals(4, LibFixture.expectedTwice(2)); }
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
                lib = { workspace = true, fixtures = true }
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
                    void uses_the_sibling_fixture() {
                        // LibFixture lives only in lib's fixtures tree — fixtures = true is required.
                        assertEquals(LibFixture.expectedTwice(3), App.useLib(3));
                    }
                }
                """);
        return ws;
    }
}
