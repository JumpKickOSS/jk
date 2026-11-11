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
import java.util.Optional;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A resource deleted since the last build is work the build does: the copy step removes its copy
 * from the classes tree (the mirror's ledger) and the jar is repackaged without it. The forecast
 * reads the same ledger, so it prices that work rather than calling the module cached.
 */
@Tag("integration")
class ForecastDeletedResourceE2eTest {

    @Test
    void a_deleted_resource_is_forecast_as_the_copy_and_repackage_the_build_does(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("forecast-deleted-resource-cache");
        Path ws = workspace(tmp);
        ClassesTreeRestoreE2eTest.lock(ws, cache);
        ClassesTreeRestoreE2eTest.build(ws, cache, "initial");
        BuildLayout layout = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));
        assertThat(layout.classesDir().resolve("gone.txt")).exists();

        assertThat(step(forecast(ws, cache), TaskNames.PACKAGE_JAR).map(TaskForecast.Task::cached))
                .as("a clean tree forecasts the jar cached")
                .hasValue(true);

        Files.delete(ws.resolve("lib/resources/gone.txt"));
        TaskForecast.Module lib = forecast(ws, cache);
        assertThat(step(lib, TaskNames.COPY_RESOURCES).map(TaskForecast.Task::cached))
                .as("the copy removes the deleted resource's copy: %s", lib.steps())
                .hasValue(false);
        assertThat(step(lib, TaskNames.PACKAGE_JAR).map(TaskForecast.Task::cached))
                .as("the jar is repackaged without it: %s", lib.steps())
                .hasValue(false);

        ClassesTreeRestoreE2eTest.build(ws, cache, "after deleting a resource");
        assertThat(layout.classesDir().resolve("gone.txt")).doesNotExist();
        try (ZipFile jar = new ZipFile(layout.mainJar().toFile())) {
            assertThat(jar.getEntry("gone.txt"))
                    .as("the build agrees: the jar lost it")
                    .isNull();
        }
    }

    private static Optional<TaskForecast.Task> step(TaskForecast.Module m, String name) {
        return m.steps().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    private static TaskForecast.Module forecast(Path ws, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, true).stream()
                .filter(m -> m.dir().getFileName().toString().equals("lib"))
                .findFirst()
                .orElseThrow();
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib"]
                """);
        Path src = Files.createDirectories(ws.resolve("lib/src/com/example/lib"));
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                """);
        Files.writeString(src.resolve("Lib.java"), """
                package com.example.lib;

                public final class Lib {
                    private Lib() {}
                }
                """);
        Path res = Files.createDirectories(ws.resolve("lib/resources"));
        Files.writeString(res.resolve("kept.txt"), "kept");
        Files.writeString(res.resolve("gone.txt"), "gone");
        return ws;
    }
}
