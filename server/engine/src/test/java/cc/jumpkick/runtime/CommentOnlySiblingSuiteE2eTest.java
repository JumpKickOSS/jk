// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A comment in a module others test against moves only its classes' line tables, which no
 * dependent suite's stamp reads: the dependents' suites are forecast cached and the build replays
 * them, transitive dependents included. A body edit reruns them all.
 */
@Tag("integration")
class CommentOnlySiblingSuiteE2eTest {

    private static final String LIB = """
            package demo.lib;

            public final class Lib {
                private Lib() {}

                public static String text(String what) {
                    String trimmed = what.strip();
                    return "[" + trimmed + "]";
                }
            }
            """;

    @Test
    void a_comment_upstream_reruns_no_dependent_suite_and_a_body_edit_reruns_them_all(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("comment-only-sibling-suite-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app", "web"]
                """);
        module(ws, "lib", "", "src/main/java/demo/lib/Lib.java", LIB, """
                package demo.lib;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class LibTest {
                    @Test
                    void wraps() {
                        assertEquals(Lib.text(" x "), Lib.text("x"));
                    }
                }
                """);
        module(ws, "app", "lib = { workspace = true }", "src/main/java/demo/app/App.java", """
                package demo.app;

                public final class App {
                    private App() {}

                    public static String hello() {
                        return demo.lib.Lib.text(" app ");
                    }
                }
                """, """
                package demo.app;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void wraps() {
                        assertEquals(demo.lib.Lib.text("app"), App.hello());
                    }
                }
                """);
        module(ws, "web", "app = { workspace = true }", "src/main/java/demo/web/Web.java", """
                package demo.web;

                public final class Web {
                    private Web() {}

                    public static String page() {
                        return demo.app.App.hello();
                    }
                }
                """, """
                package demo.web;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class WebTest {
                    @Test
                    void renders() {
                        assertEquals(demo.app.App.hello(), Web.page());
                    }
                }
                """);
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
        assertThat(build(ws, cache).success).as("first build").isTrue();
        Path lib = ws.resolve("lib/src/main/java/demo/lib/Lib.java");

        Files.writeString(lib, LIB.replace("""
                        String trimmed = what.strip();""", """
                        // Callers pass padded names.

                        String trimmed = what.strip();"""));
        Map<String, TaskForecast.Module> forecast = forecast(ws, cache);
        assertThat(step(forecast.get("lib"), TaskNames.COMPILE_JAVA).cached())
                .as("the edited module compiles")
                .isFalse();
        for (String dependent : List.of("app", "web")) {
            TaskForecast.Task suite = step(forecast.get(dependent), TaskNames.RUN_TESTS);
            assertThat(suite.cached())
                    .as("%s's suite after a comment in lib: %s", dependent, suite.text())
                    .isTrue();
            assertThat(suite.text()).contains("comment-only edit in");
        }
        Run comment = build(ws, cache);
        assertThat(comment.success).as("build after a comment").isTrue();
        assertThat(comment.runTests("lib"))
                .as("the edited module's own suite keys its classes whole")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(comment.runTests("app")).as("app's suite replays").isIn(null, TaskStatus.SKIPPED);
        assertThat(comment.runTests("web")).as("web's suite replays").isIn(null, TaskStatus.SKIPPED);

        Files.writeString(lib, LIB.replace("\"[\"", "\"(\"").replace("\"]\"", "\")\""));
        Map<String, TaskForecast.Module> afterBody = forecast(ws, cache);
        for (String dependent : List.of("app", "web")) {
            TaskForecast.Task suite = step(afterBody.get(dependent), TaskNames.RUN_TESTS);
            assertThat(suite.cached())
                    .as("%s's suite after a body edit in lib: %s", dependent, suite.text())
                    .isFalse();
        }
        Run body = build(ws, cache);
        assertThat(body.success).as("build after a body edit").isTrue();
        assertThat(body.runTests("app")).as("app's suite reruns").isEqualTo(TaskStatus.SUCCESS);
        assertThat(body.runTests("web")).as("web's suite reruns").isEqualTo(TaskStatus.SUCCESS);
    }

    private static void module(Path ws, String name, String dependency, String mainPath, String main, String test)
            throws Exception {
        Path m = Files.createDirectories(ws.resolve(name));
        Files.writeString(m.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25

                [dependencies]
                %s

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(name, dependency));
        Path source = m.resolve(mainPath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, main);
        String cls = test.substring(test.indexOf("class ") + 6, test.indexOf(" {", test.indexOf("class ")));
        String pkg =
                test.substring(test.indexOf("package ") + 8, test.indexOf(';')).replace('.', '/');
        Path dir = Files.createDirectories(m.resolve("src/test/java").resolve(pkg));
        Files.writeString(dir.resolve(cls + ".java"), test);
    }

    private static TaskForecast.Task step(TaskForecast.@Nullable Module m, String name) {
        if (m == null) throw new AssertionError("no forecast for the module of " + name);
        return m.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + m.steps()));
    }

    private static Map<String, TaskForecast.Module> forecast(Path ws, Path cache) throws Exception {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        Map<String, TaskForecast.Module> out = new ConcurrentHashMap<>();
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            for (TaskForecast.Module m : ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache))
                out.put(m.dir().getFileName().toString(), m);
        });
        return out;
    }

    /** One workspace build, as the engine's job runs it. */
    private static Run build(Path ws, Path cache) throws Exception {
        Session session = Session.defaults().withCacheDir(cache);
        Run run = new Run();
        AtomicReference<WorkspaceResult> result = new AtomicReference<>();
        SessionContext.runWhere(
                session,
                () -> result.set(Request.detached()
                        .with(SettledReads.SLOT, new SettledReads())
                        .call(() -> WorkspaceExecute.buildWorkspace(
                                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                                run))));
        run.success = result.get().success();
        return run;
    }

    /** Each module's run-tests status, by module name, and whether the build succeeded. */
    private static final class Run implements WorkspaceBuildListener {
        final Map<String, TaskStatus> runTests = new ConcurrentHashMap<>();
        boolean success;

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    if (step.equals(TaskNames.RUN_TESTS)) runTests.put(name, status);
                }
            };
        }

        @Nullable
        TaskStatus runTests(String module) {
            return runTests.get(module);
        }
    }
}
