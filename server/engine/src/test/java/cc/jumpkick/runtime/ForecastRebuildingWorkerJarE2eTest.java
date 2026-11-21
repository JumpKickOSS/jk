// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
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
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite whose stamp hashes a sibling's worker jar ({@code [build] test-plugin-jars}) is forecast
 * to run while that sibling is rebuilding, before the jar is. The sibling is only a build-order
 * prerequisite, so it prices no compile in the consumer, and the forecast keyed the suite on the jar
 * still on disk and called it cached; the build then rebuilt the jar and ran the suite: jk-engine's
 * 4,794 tests after a jk-generator edit were missing from {@code jk explain} until the plugin was
 * rebuilt. The forecast now names the worker, and the build agrees.
 */
@Tag("integration")
class ForecastRebuildingWorkerJarE2eTest {

    private static final String GENERATOR = """
            package com.example.gen;

            public final class Gen {
                private Gen() {}

                public static int two() {
                    return 2;
                }
            }
            """;

    @Test
    void a_worker_sibling_edit_forecasts_the_suite_that_hashes_its_jar(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("forecast-rebuilding-worker-jar-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);
        build(ws, cache, "initial");
        assertThat(step(forecast(ws, cache, "host"), TaskNames.RUN_TESTS).cached())
                .as("an unchanged tree forecasts the suite cached")
                .isTrue();

        Files.writeString(generatorSource(ws), GENERATOR.replace("return 2;", "return 1 + 1;"));
        TaskForecast.Task runTests = step(forecast(ws, cache, "host"), TaskNames.RUN_TESTS);
        assertThat(runTests.cached())
                .as("the worker jar the suite's stamp hashes is rebuilding: %s", runTests.text())
                .isFalse();
        assertThat(runTests.text()).contains("worker jk.generator.plugin.jar changed");

        build(ws, cache, "after the generator edit");
        assertThat(step(forecast(ws, cache, "host"), TaskNames.RUN_TESTS).cached())
                .as("the build ran the suite against the rebuilt jar and stored it")
                .isTrue();
    }

    /** A module that is only a build-order prerequisite and makes no worker jar the stamp hashes stays priced as before. */
    @Test
    void a_build_order_sibling_that_makes_no_worker_prices_no_suite(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path host = ws.resolve("host");
        assertThat(TestStampInputs.rebuildingWorker(
                        host, JkBuildParser.parse(host.resolve("jk.toml")), List.of(ws.resolve("other"))))
                .isNull();
        assertThat(TestStampInputs.rebuildingWorker(
                        host, JkBuildParser.parse(host.resolve("jk.toml")), List.of(ws.resolve("generator"))))
                .isEqualTo("jk.generator.plugin.jar");
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
        return ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache).stream()
                .filter(m -> m.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow();
    }

    private static void lock(Path ws, Path cache) throws Exception {
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
    }

    private static void build(Path ws, Path cache, String what) {
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
    }

    private static Path generatorSource(Path ws) {
        return ws.resolve("generator/src/com/example/gen/Gen.java");
    }

    /**
     * {@code generator}, named as the first-party worker {@code jk-generator} is, and {@code host},
     * whose tests fork it: {@code test-plugin-jars = ["generator"]}, a build-order edge and a worker
     * jar in the run-tests stamp; {@code other}, a module that makes no worker.
     */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["generator", "other", "host"]
                """);
        Path gen = Files.createDirectories(ws.resolve("generator"));
        Files.writeString(gen.resolve("jk.toml"), """
                group   = "com.example"
                name    = "jk-generator"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(
                Files.createDirectories(generatorSource(ws).getParent()).resolve("Gen.java"), GENERATOR);
        Path other = Files.createDirectories(ws.resolve("other"));
        Files.writeString(other.resolve("jk.toml"), """
                group   = "com.example"
                name    = "other"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(
                Files.createDirectories(other.resolve("src/com/example/other")).resolve("Other.java"),
                "package com.example.other;\n\npublic final class Other {}\n");
        Path host = Files.createDirectories(ws.resolve("host"));
        Files.writeString(host.resolve("jk.toml"), """
                group   = "com.example"
                name    = "host"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [build]
                test-plugin-jars = ["generator"]
                order-after = ["other"]
                """);
        Files.writeString(
                Files.createDirectories(host.resolve("src/com/example/host")).resolve("Host.java"),
                "package com.example.host;\n\npublic final class Host {}\n");
        Files.writeString(
                Files.createDirectories(host.resolve("test/src/com/example/host"))
                        .resolve("HostCheck.java"),
                """
                package com.example.host;

                final class HostCheck {
                    private HostCheck() {}
                }
                """);
        return ws;
    }
}
