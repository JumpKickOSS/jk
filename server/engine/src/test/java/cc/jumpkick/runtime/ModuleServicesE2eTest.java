// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [build] module-services = true} end to end: the classes tree and the jar carry a {@code
 * META-INF/services} file for the descriptor's {@code provides}; a second build skips the step and
 * the jar; a {@code provides} removed from {@code module-info.java} takes its file out of both; a
 * resource that names other providers fails the build.
 */
@Tag("integration")
class ModuleServicesE2eTest {

    private static final String SERVICE = "META-INF/services/com.example.Greeter";

    @TempDir
    Path tmp;

    @Test
    void the_jar_carries_the_provides_and_a_removed_provides_leaves_it() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("module-services-cache");
        lock(ws, cache);
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));

        Steps build1 = build(ws, cache, "first", null);
        assertThat(build1.status("lib", TaskNames.MODULE_SERVICES)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Files.readString(lib.classesDir().resolve(SERVICE)))
                .isEqualTo("com.example.Hello\ncom.example.Hi\n");
        assertThat(jarEntry(lib.mainJar(), SERVICE)).isEqualTo("com.example.Hello\ncom.example.Hi\n");

        Steps build2 = build(ws, cache, "second", Set.of(ws.resolve("lib")));
        assertThat(build2.status("lib", TaskNames.MODULE_SERVICES)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(build2.status("lib", TaskNames.PACKAGE_JAR)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(forecast(ws, cache, "lib", TaskNames.MODULE_SERVICES)).isEmpty();

        Files.writeString(ws.resolve("lib/src/module-info.java"), "module com.example { exports com.example; }\n");
        Steps build3 = build(ws, cache, "third", null);
        assertThat(build3.status("lib", TaskNames.MODULE_SERVICES)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(lib.classesDir().resolve(SERVICE)).doesNotExist();
        assertThat(jarEntry(lib.mainJar(), SERVICE)).isNull();
    }

    @Test
    void a_resource_naming_other_providers_fails_the_build() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("module-services-cache");
        lock(ws, cache);
        Path resource = Files.createDirectories(ws.resolve("lib/resources/META-INF/services"));
        Files.writeString(resource.resolve("com.example.Greeter"), "com.example.Hello\n");

        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), steps);
        assertThat(result.success()).isFalse();
        assertThat(steps.status("lib", TaskNames.MODULE_SERVICES)).isEqualTo(TaskStatus.FAIL);
        assertThat(String.join("\n", steps.errors))
                .contains(SERVICE)
                .contains("[com.example.Hello]")
                .contains("[com.example.Hello, com.example.Hi]");
    }

    private static @Nullable String jarEntry(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            return entry == null ? null : new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Optional<TaskForecast.Task> forecast(Path ws, Path cache, String module, String step)
            throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        List<TaskForecast.Module> plan = ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
        return plan.stream()
                .filter(x -> x.dir().getFileName().toString().equals(module))
                .flatMap(m -> m.steps().stream())
                .filter(s -> s.name().equals(step))
                .findFirst();
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
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("lib/jk-lock.toml"));
    }

    /** Per module and step: final status. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    statusByModuleStep.put(name + "/" + step, status);
                }

                @Override
                public void error(String step, String code, String message) {
                    errors.add(message);
                }
            };
        }

        @Nullable
        TaskStatus status(String module, String step) {
            return statusByModuleStep.get(module + "/" + step);
        }
    }

    /** A one-library workspace whose module-info provides one service with two providers. */
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
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [build]
                module-services = true

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/module-info.java"), """
                module com.example {
                    exports com.example;
                    provides com.example.Greeter with com.example.Hello, com.example.Hi;
                }
                """);
        Files.writeString(src.resolve("Greeter.java"), """
                package com.example;

                /** Says hello. */
                public interface Greeter {
                    /** The greeting. */
                    String greet();
                }
                """);
        for (String name : List.of("Hello", "Hi")) {
            Files.writeString(src.resolve(name + ".java"), """
                    package com.example;

                    /** A greeter. */
                    public final class %s implements Greeter {
                        /** Public for ServiceLoader. */
                        public %s() {}

                        @Override
                        public String greet() {
                            return "%s";
                        }
                    }
                    """.formatted(name, name, name));
        }
        return ws;
    }
}
