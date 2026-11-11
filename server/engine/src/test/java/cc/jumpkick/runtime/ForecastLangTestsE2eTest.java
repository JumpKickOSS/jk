// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * compile-test's Kotlin and Groovy passes are forecast by the keys the build's passes run under,
 * where they were assumed fresh whenever main was: an edited Kotlin or Groovy test forecast
 * compile-test cached and the build compiled it.
 */
@Tag("integration")
class ForecastLangTestsE2eTest {

    private static final String KOTLIN_TEST = """
            package com.example

            class AppCheck {
                fun two() = App.two()
            }
            """;

    private static final String GROOVY_TEST = """
            package com.example

            class AppCheck {
                int two() { App.two() }
            }
            """;

    @Test
    void an_edited_kotlin_test_is_forecast_as_the_compile_the_build_runs(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp, "kotlin  = \"^2.4.10\"", "App.kt", """
                package com.example

                object App {
                    fun two() = 2
                }
                """, "AppCheck.kt", KOTLIN_TEST);
        editedTestCompiles(ws, "AppCheck.kt", KOTLIN_TEST.replace("App.two()", "App.two() + 1"), "Kotlin test source");
    }

    @Test
    void an_edited_groovy_test_is_forecast_as_the_compile_the_build_runs(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp, "groovy  = \"5.0.4\"", "App.java", """
                package com.example;

                public final class App {
                    private App() {}

                    public static int two() {
                        return 2;
                    }
                }
                """, "AppCheck.groovy", GROOVY_TEST);
        editedTestCompiles(
                ws, "AppCheck.groovy", GROOVY_TEST.replace("App.two() }", "App.two() + 1 }"), "Groovy test source");
    }

    private static void editedTestCompiles(Path ws, String testFile, String edited, String text) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        lock(ws, cache);
        build(ws, cache);
        assertThat(forecast(ws, cache).cached())
                .as("unchanged tests hit their pass's record")
                .isTrue();

        Files.writeString(ws.resolve("app/test/src/com/example").resolve(testFile), edited);
        TaskForecast.Task step = forecast(ws, cache);
        assertThat(step.cached()).as("an edited test compiles: %s", step.text()).isFalse();
        assertThat(step.text()).contains(text);

        build(ws, cache);
        assertThat(forecast(ws, cache).cached())
                .as("the build compiled it under the key the forecast named")
                .isTrue();
    }

    private static TaskForecast.Task forecast(Path ws, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        TaskForecast.Module m = ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache).stream()
                .filter(x -> x.dir().getFileName().toString().equals("app"))
                .findFirst()
                .orElseThrow();
        return m.steps().stream()
                .filter(s -> s.name().equals(TaskNames.COMPILE_TEST))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no compile-test in " + m.steps()));
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
    }

    private static void build(Path ws, Path cache) {
        WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
    }

    private static Path workspace(Path tmp, String langLine, String mainFile, String main, String testFile, String test)
            throws IOException {
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
                %s

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(langLine));
        Files.writeString(
                Files.createDirectories(app.resolve("src/com/example")).resolve(mainFile), main);
        Files.writeString(
                Files.createDirectories(app.resolve("test/src/com/example")).resolve(testFile), test);
        return ws;
    }
}
