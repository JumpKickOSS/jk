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
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.TaskForecast;
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
 * A library ({@code lib}: main sources, no {@code [application]}) packages {@code -sources.jar}
 * and {@code -javadoc.jar} beside its jar; an application ({@code app}) does not. A javadoc
 * warning rides the run's diagnostics with its file and line; the second build restores both jars
 * from the action cache and {@code jk explain} forecasts the javadoc step as cached.
 */
@Tag("integration")
class LibraryArtifactsE2eTest {

    @TempDir
    Path tmp;

    @Test
    void a_library_ships_sources_and_javadoc_jars_and_an_application_does_not() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("library-artifacts-cache");
        lock(ws, cache);
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));

        Steps first = build(ws, cache, "first");
        assertThat(lib.sourcesJar()).isRegularFile();
        assertThat(lib.javadocJar()).isRegularFile();
        assertThat(entries(lib.sourcesJar())).contains("com/example/One.java", "com/example/Two.java");
        assertThat(entries(lib.javadocJar()))
                .contains("index.html", "com/example/One.html")
                .anyMatch(e -> e.equals("element-list") || e.equals("package-list"));
        assertThat(app.mainJar()).isRegularFile();
        assertThat(app.sourcesJar()).doesNotExist();
        assertThat(app.javadocJar()).doesNotExist();
        assertThat(first.status("app", TaskNames.PACKAGE_JAVADOC)).isNull();
        assertThat(first.status("lib", TaskNames.PACKAGE_JAVADOC)).isEqualTo(TaskStatus.SUCCESS);
        // The unknown tag in One.java is a warning in the results, located at its line.
        assertThat(first.warnings("lib", TaskNames.PACKAGE_JAVADOC))
                .anyMatch(w -> w.contains("One.java:6: warning: unknown tag"));

        // A second build is a cache hit for both library jars, and explain agrees. lib is
        // scheduled by hand: left to the forecast, an up-to-date module runs no plan at all.
        Steps second = build(ws, cache, "second", Set.of(ws.resolve("lib")));
        assertThat(second.status("lib", TaskNames.PACKAGE_JAVADOC)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(second.status("lib", TaskNames.PACKAGE_SOURCES)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(second.labels("lib", TaskNames.PACKAGE_JAVADOC)).anyMatch(l -> l.contains("up-to-date"));
        assertThat(second.warnings("lib", TaskNames.PACKAGE_JAVADOC)).isEmpty();
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
        List<TaskForecast.Module> plan = TaskForecaster.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
        TaskForecast.Module m = plan.stream()
                .filter(x -> x.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow();
        return m.steps().stream()
                .filter(s -> s.name().equals(step))
                .findFirst()
                .orElseThrow(() -> new AssertionError(module + " forecasts no " + step + ": " + m.steps()));
    }

    private static Steps build(Path ws, Path cache, String what) {
        return build(ws, cache, what, null);
    }

    /** {@code dirty} names the modules to schedule regardless of the forecast; null forecasts. */
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
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("lib/jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
    }

    /** Per module and step: final status, labels, and warnings. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();
        private final Map<String, List<String>> labelsByModuleStep = new ConcurrentHashMap<>();
        private final Map<String, List<String>> warningsByModuleStep = new ConcurrentHashMap<>();

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
                public void warn(String step, String code, String message) {
                    warningsByModuleStep
                            .computeIfAbsent(name + "/" + step, k -> new ArrayList<>())
                            .add(message);
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

        List<String> warnings(String module, String step) {
            return List.copyOf(warningsByModuleStep.getOrDefault(module + "/" + step, List.of()));
        }
    }

    /** lib is a documented library with one unknown javadoc tag; app is an application over it. */
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
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path libSrc = Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(libSrc.resolve("One.java"), """
                package com.example;

                /**
                 * One.
                 *
                 * @custom an unknown tag javadoc warns about
                 */
                public final class One {
                    private One() {}

                    /** The answer. */
                    public static int one() {
                        return 1;
                    }
                }
                """);
        Files.writeString(libSrc.resolve("Two.java"), """
                package com.example;

                /** Two. */
                public final class Two {
                    private Two() {}

                    /** Twice {@link One#one()}. */
                    public static int two() {
                        return 2 * One.one();
                    }
                }
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [application]
                main = "com.example.app.Main"

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path appSrc = Files.createDirectories(app.resolve("src/com/example/app"));
        Files.writeString(appSrc.resolve("Main.java"), """
                package com.example.app;

                /** Prints two. */
                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(com.example.Two.two());
                    }
                }
                """);
        return ws;
    }
}
