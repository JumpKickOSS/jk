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
 * A Kotlin library's javadoc jar is Dokka's output, a mixed module's documents both languages, and
 * the second build restores the jar from the action cache with {@code jk explain} agreeing.
 */
@Tag("integration")
class KotlinJavadocE2eTest {

    @TempDir
    Path tmp;

    @Test
    void a_kotlin_library_and_a_mixed_module_ship_dokka_javadoc_jars() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("kotlin-javadoc-cache");
        lock(ws, cache);
        BuildLayout kt = BuildLayout.of(ws, ws.resolve("kt"), JkBuildParser.parse(ws.resolve("kt/jk.toml")));
        BuildLayout mixed = BuildLayout.of(ws, ws.resolve("mixed"), JkBuildParser.parse(ws.resolve("mixed/jk.toml")));

        Steps first = build(ws, cache, "first", null);
        assertThat(first.status("kt", TaskNames.PACKAGE_JAVADOC)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(first.labels("kt", TaskNames.PACKAGE_JAVADOC)).anyMatch(l -> l.startsWith("dokka "));
        assertThat(kt.javadocJar()).isRegularFile();
        TreeSet<String> ktEntries = entries(kt.javadocJar());
        assertThat(ktEntries).contains("index.html");
        assertThat(ktEntries).as("Dokka documented the Kotlin type").anyMatch(e -> e.contains("Greeter"));
        assertThat(ktEntries).noneMatch(e -> e.startsWith("README"));

        TreeSet<String> mixedEntries = entries(mixed.javadocJar());
        assertThat(mixedEntries).as("both languages").anyMatch(e -> e.contains("Shout"));
        assertThat(mixedEntries).anyMatch(e -> e.contains("Loud"));

        Steps second = build(ws, cache, "second", Set.of(ws.resolve("kt")));
        assertThat(second.status("kt", TaskNames.PACKAGE_JAVADOC)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(forecast(ws, cache, "kt", TaskNames.PACKAGE_JAVADOC).cached())
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
        for (String member : List.of("kt", "mixed")) {
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

    /** kt is a Kotlin-only library; mixed holds a Kotlin type and a Java type over it. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                kotlin  = "latest"
                java    = 25

                [workspace]
                modules = ["kt", "mixed"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path kt = Files.createDirectories(ws.resolve("kt"));
        Files.writeString(kt.resolve("jk.toml"), """
                name = "kt"
                """);
        Path ktSrc = Files.createDirectories(kt.resolve("src/main/kotlin/com/example/kt"));
        Files.writeString(ktSrc.resolve("Greeter.kt"), """
                package com.example.kt

                /** Greets by name. */
                class Greeter(private val greeting: String = "Hello") {
                    /** The greeting for [name]. */
                    fun greet(name: String): String = "$greeting, $name!"
                }
                """);
        Path mixed = Files.createDirectories(ws.resolve("mixed"));
        Files.writeString(mixed.resolve("jk.toml"), """
                name = "mixed"

                [dependencies]
                kt = { workspace = true }
                """);
        Path mixedKt = Files.createDirectories(mixed.resolve("src/main/kotlin/com/example/mixed"));
        Files.writeString(mixedKt.resolve("Shout.kt"), """
                package com.example.mixed

                import com.example.kt.Greeter

                /** Greets loudly. */
                class Shout {
                    /** [Greeter.greet] in capitals. */
                    fun shout(name: String): String = Greeter().greet(name).uppercase()
                }
                """);
        Path mixedJava = Files.createDirectories(mixed.resolve("src/main/java/com/example/mixed"));
        Files.writeString(mixedJava.resolve("Loud.java"), """
                package com.example.mixed;

                /** Java over the Kotlin type. */
                public final class Loud {
                    private Loud() {}

                    /** {@link Shout#shout} twice. */
                    public static String twice(String name) {
                        String once = new Shout().shout(name);
                        return once + " " + once;
                    }
                }
                """);
        return ws;
    }
}
