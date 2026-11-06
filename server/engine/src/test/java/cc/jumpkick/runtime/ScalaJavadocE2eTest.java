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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Scala library ships its Scala sources and its scaladoc: the sources jar holds every main root's
 * files, the javadoc jar is scaladoc's site, and the second build restores it from the action cache
 * with {@code jk explain} agreeing.
 */
@Tag("integration")
class ScalaJavadocE2eTest {

    @TempDir
    Path tmp;

    @Test
    void a_scala_library_ships_its_scala_sources_and_scaladoc() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("scala-javadoc-cache");
        lock(ws, cache);
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));

        Steps first = build(ws, cache, "first", null);
        assertThat(lib.sourcesJar()).isRegularFile();
        assertThat(entries(lib.sourcesJar()))
                .contains(
                        "com/example/lib/Greeter.scala",
                        "com/example/lib/package-info.java",
                        "com/example/lib/Shout.java");

        assertThat(first.status("lib", TaskNames.PACKAGE_JAVADOC)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(first.labels("lib", TaskNames.PACKAGE_JAVADOC)).anyMatch(l -> l.startsWith("scaladoc "));
        TreeSet<String> docs = entries(lib.javadocJar());
        assertThat(docs).contains("index.html");
        assertThat(docs).as("scaladoc documented the Scala type").anyMatch(e -> e.contains("Greeter"));
        assertThat(docs).noneMatch(e -> e.startsWith("README"));

        Steps second = build(ws, cache, "second", Set.of(ws.resolve("lib")));
        assertThat(second.status("lib", TaskNames.PACKAGE_JAVADOC)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(forecast(ws, cache, "lib", TaskNames.PACKAGE_JAVADOC).cached())
                .isTrue();
    }

    private static TreeSet<String> entries(Path jar) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    private static TaskForecast.Task forecast(Path ws, Path cache, String module, String step) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        List<TaskForecast.Module> plan = ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
        TaskForecast.Module m = plan.stream()
                .filter(x -> x.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow();
        return m.steps().stream()
                .filter(s -> s.name().equals(step))
                .findFirst()
                .orElseThrow(() -> new AssertionError(module + " forecasts no " + step + ": " + m.steps()));
    }

    private static Steps build(Path ws, Path cache, String what, @Nullable Set<Path> dirty) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, dirty, false, false), steps);
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
        return steps;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        for (String member : List.of("lib")) {
            Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(member + "/jk-lock.toml"));
        }
    }

    /** Per module and step: final status and labels. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();
        private final Map<String, List<String>> labelsByModuleStep = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void label(String step, String label) {
                    labelsByModuleStep
                            .computeIfAbsent(name + "/" + step, k -> new ArrayList<>())
                            .add(label);
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    statusByModuleStep.put(name + "/" + step, status);
                }
            };
        }

        @Nullable
        TaskStatus status(String module, String step) {
            return statusByModuleStep.get(module + "/" + step);
        }

        List<String> labels(String module, String step) {
            return List.copyOf(labelsByModuleStep.getOrDefault(module + "/" + step, List.of()));
        }
    }

    /** lib is a Scala library with a package-info.java beside its Scala and a Java type in src/main/java. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                scala   = "3.8.4"
                java    = 25

                [workspace]
                modules = ["lib"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"
                """);
        Path scala = Files.createDirectories(lib.resolve("src/main/scala/com/example/lib"));
        Files.writeString(scala.resolve("Greeter.scala"), """
                package com.example.lib

                /** Greets by name. */
                class Greeter(greeting: String = "Hello"):
                  /** The greeting for `name`. */
                  def greet(name: String): String = s"$greeting, $name!"
                """);
        Files.writeString(scala.resolve("package-info.java"), """
                /** Greetings. */
                package com.example.lib;
                """);
        Path java = Files.createDirectories(lib.resolve("src/main/java/com/example/lib"));
        Files.writeString(java.resolve("Shout.java"), """
                package com.example.lib;

                /** Java over the Scala type. */
                public final class Shout {
                    private Shout() {}

                    /** The greeting in capitals. */
                    public static String shout(String name) {
                        return new Greeter("Hello").greet(name).toUpperCase();
                    }
                }
                """);
        return ws;
    }
}
