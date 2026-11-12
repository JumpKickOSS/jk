// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module's own edit reaches its test compile only when it moves the module's API: the test
 * compile keys the main classes by ABI. A body-only edit recompiles main and leaves the tests'
 * compile hitting, and the forecast says so instead of pricing a recompile the build never runs
 * (jk's own engine: 22 s of phantom compile-test on the critical path); an API edit is the recompile
 * it is.
 */
@Tag("integration")
class ForecastBodyOnlyOwnTestsE2eTest {

    private static final String APP = """
            package com.example;

            public final class App {
                private App() {}

                public static int two() {
                    return 2;
                }
            }
            """;

    @Test
    void a_body_only_edit_leaves_the_test_compile_cached_and_the_build_agrees(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("forecast-body-only-own-tests-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);
        build(ws, cache, "initial");
        Path testClass = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")))
                .testClassesDir()
                .resolve("com/example/AppCheck.class");
        assertThat(testClass).exists();
        FileTime before = Files.getLastModifiedTime(testClass);

        Files.writeString(appSource(ws), APP.replace("return 2;", "return 1 + 1;"));
        TaskForecast.Module app = forecast(ws, cache);
        assertThat(step(app, TaskNames.COMPILE_JAVA).cached())
                .as("the edited module compiles")
                .isFalse();
        TaskForecast.Task compileTest = step(app, TaskNames.COMPILE_TEST);
        assertThat(compileTest.cached())
                .as("a body-only edit leaves the ABI-keyed test compile hitting: %s", compileTest.text())
                .isTrue();
        assertThat(step(app, TaskNames.RUN_TESTS).cached())
                .as("the suite still runs: the classes it tests changed")
                .isFalse();

        build(ws, cache, "after a body-only edit");
        assertThat(Files.getLastModifiedTime(testClass))
                .as("the build agrees: the tests were not recompiled")
                .isEqualTo(before);

        Files.writeString(appSource(ws), APP.replace("""
                    public static int two() {""", """
                    public static int three() {
                        return 3;
                    }

                    public static int two() {"""));
        TaskForecast.Task afterApiEdit = step(forecast(ws, cache), TaskNames.COMPILE_TEST);
        assertThat(afterApiEdit.cached())
                .as("an API edit is forecast as the test recompile it is: %s", afterApiEdit.text())
                .isFalse();
    }

    private static TaskForecast.Task step(TaskForecast.Module m, String name) {
        return m.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + m.steps()));
    }

    private static TaskForecast.Module forecast(Path ws, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache).stream()
                .filter(m -> m.dir().getFileName().toString().equals("app"))
                .findFirst()
                .orElseThrow();
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
    }

    private static void build(Path ws, Path cache, String what) {
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
    }

    private static Path appSource(Path ws) {
        return ws.resolve("app/src/com/example/App.java");
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(
                Files.createDirectories(app.resolve("src/com/example")).resolve("App.java"), APP);
        Files.writeString(
                Files.createDirectories(app.resolve("test/src/com/example")).resolve("AppCheck.java"), """
                package com.example;

                final class AppCheck {
                    private AppCheck() {}

                    static int four() {
                        return App.two() * 2;
                    }
                }
                """);
        return ws;
    }
}
