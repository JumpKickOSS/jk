// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dependency's edit cascades into a consumer's compile only when it moves the dependency's API:
 * the consumer's compile key reads the classpath by ABI. A body-only edit is forecast cached for
 * the consumer and the build agrees (the consumer's classes are not rewritten); an API edit is
 * forecast as the recompile it is.
 */
@Tag("integration")
class ForecastBodyOnlyCascadeE2eTest {

    private static final String LIB_A = """
            package com.example.lib;

            public final class Lib {
                private Lib() {}

                public static String greet() {
                    return "a";
                }
            }
            """;

    @Test
    void a_body_only_edit_leaves_the_consumers_compile_cached_and_the_build_agrees(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("forecast-body-only-cascade-cache");
        Path ws = workspace(tmp);
        ClassesTreeRestoreE2eTest.lock(ws, cache);
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
        ClassesTreeRestoreE2eTest.build(ws, cache, "initial");
        Path appClass = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")))
                .classesDir()
                .resolve("com/example/app/App.class");
        FileTime before = Files.getLastModifiedTime(appClass);

        Files.writeString(libSource(ws), LIB_A.replace("\"a\"", "\"b\""));
        TaskForecast.Module lib = forecast(ws, cache, "lib");
        TaskForecast.Module app = forecast(ws, cache, "app");
        assertThat(step(lib, TaskNames.COMPILE_JAVA).cached())
                .as("the edited module compiles")
                .isFalse();
        TaskForecast.Task appCompile = step(app, TaskNames.COMPILE_JAVA);
        assertThat(appCompile.cached())
                .as("a body-only edit upstream leaves the ABI-keyed compile hitting: %s", appCompile.text())
                .isTrue();
        assertThat(appCompile.text()).contains("body-only edit in lib");
        assertThat(step(app, TaskNames.PACKAGE_JAR).cached())
                .as("unchanged classes repackage nothing")
                .isTrue();
        assertThat(step(app, TaskNames.DEPENDENCY_CHECK).cached())
                .as("the hint never skips the consumer: it schedules, so the build's keys check lib's real output")
                .isFalse();

        ClassesTreeRestoreE2eTest.build(ws, cache, "after a body-only edit in lib");
        assertThat(Files.getLastModifiedTime(appClass))
                .as("the build agrees: the consumer's classes were not recompiled")
                .isEqualTo(before);

        Files.writeString(libSource(ws), LIB_A.replace("""
                    public static String greet() {""", """
                    public static int added() {
                        return 1;
                    }

                    public static String greet() {"""));
        TaskForecast.Task afterApiEdit = step(forecast(ws, cache, "app"), TaskNames.COMPILE_JAVA);
        assertThat(afterApiEdit.cached())
                .as("an API edit upstream is forecast as the recompile it is: %s", afterApiEdit.text())
                .isFalse();
        assertThat(afterApiEdit.text()).startsWith(TaskForecast.LIKELY_RECOMPILE);
    }

    private static TaskForecast.Task step(TaskForecast.Module m, String name) {
        return m.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + m.steps()));
    }

    private static TaskForecast.Module forecast(Path ws, Path cache, String module) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, true).stream()
                .filter(m -> m.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow();
    }

    private static Path libSource(Path ws) {
        return ws.resolve("lib/src/com/example/lib/Lib.java");
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib/src/com/example/lib"));
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                """);
        Files.writeString(lib.resolve("Lib.java"), LIB_A);
        Path app = Files.createDirectories(ws.resolve("app/src/com/example/app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }
                """);
        Files.writeString(app.resolve("App.java"), """
                package com.example.app;

                import com.example.lib.Lib;

                public final class App {
                    private App() {}

                    public static String hello() {
                        return Lib.greet();
                    }
                }
                """);
        return ws;
    }
}
