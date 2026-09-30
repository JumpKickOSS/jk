// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [multi-release]} end to end: the release source set compiles against the main classes
 * (its {@code module-info.java} patching them), the jar carries it under {@code
 * META-INF/versions/21/} with {@code Multi-Release: true}, the suite's directory classpath reads the
 * versioned classes first, and an unchanged second build compiles and packages nothing.
 */
@Tag("integration")
class MultiReleaseE2eTest {

    @TempDir
    Path tmp;

    @Test
    void the_release_set_lands_under_meta_inf_versions_and_ahead_of_main_on_the_suite_classpath() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("multi-release-cache");
        lock(ws, cache);
        JkBuild lib = JkBuildParser.parse(ws.resolve("lib/jk.toml"));
        BuildLayout layout = BuildLayout.of(ws, ws.resolve("lib"), lib);

        Steps first = build(ws, cache, "first");
        assertThat(first.status("lib", TaskNames.COMPILE_VERSIONS)).isEqualTo(TaskStatus.SUCCESS);

        try (JarFile jar = new JarFile(layout.mainJar().toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Multi-Release"))
                    .isEqualTo("true");
            assertThat(jar.getEntry("META-INF/versions/21/com/example/Greeting.class"))
                    .isNotNull();
            assertThat(jar.getEntry("META-INF/versions/21/com/example/Only21.class"))
                    .isNotNull();
            assertThat(jar.getEntry("com/example/Greeting.class")).isNotNull();
            var descriptor = jar.getEntry("META-INF/versions/21/module-info.class");
            assertThat(descriptor).as("the versioned descriptor").isNotNull();
            try (var in = jar.getInputStream(descriptor)) {
                assertThat(ModuleDescriptor.read(in).exports())
                        .extracting(ModuleDescriptor.Exports::source)
                        .containsExactly("com.example");
            }
        }
        assertThat(greeting(List.of(layout.mainJar())))
                .as("the packaged jar on JDK 25")
                .isEqualTo("21");

        List<Path> suite = PlannerVersions.launchClasspath(lib, layout, 25, layout.classesDir(), List.of());
        assertThat(suite).containsExactly(layout.versionedClassesDir(21), layout.classesDir());
        assertThat(greeting(suite)).as("the suite's directory classpath").isEqualTo("21");
        assertThat(PlannerVersions.launchClasspath(lib, layout, 17, layout.classesDir(), List.of()))
                .as("a JDK below the release reads the base classes alone")
                .containsExactly(layout.classesDir());

        // Up to date: the forecast skips the module, or its steps answer from their records.
        Steps second = build(ws, cache, "second");
        assertThat(second.status("lib", TaskNames.COMPILE_VERSIONS)).isIn(null, TaskStatus.SKIPPED);
        assertThat(second.status("lib", TaskNames.PACKAGE_JAR)).isIn(null, TaskStatus.SKIPPED);

        Files.writeString(ws.resolve("lib/src/main/java21/com/example/Greeting.java"), greetingSource("21b"));
        Steps third = build(ws, cache, "third");
        assertThat(third.status("lib", TaskNames.COMPILE_VERSIONS)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(third.status("lib", TaskNames.PACKAGE_JAR))
                .as("a versioned source edit repackages")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(greeting(List.of(layout.mainJar()))).isEqualTo("21b");
    }

    private static String greeting(List<Path> classpath) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (Path p : classpath) urls.add(p.toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), null)) {
            return (String)
                    loader.loadClass("com.example.Greeting").getMethod("text").invoke(null);
        }
    }

    private static Steps build(Path ws, Path cache, String what) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), steps);
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
        return steps;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
    }

    /** Per module and step: final status. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
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
    }

    private static String greetingSource(String text) {
        return """
                package com.example;

                /** The greeting. */
                public final class Greeting {
                    private Greeting() {}

                    /** Which build of the class answered. */
                    public static String text() {
                        return "%s";
                    }
                }
                """.formatted(text);
    }

    /** A one-library workspace at Java 17 whose Java 21 set replaces one class, adds one, and names the module. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 17

                [workspace]
                modules = ["lib"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 17
                javadoc = false

                [multi-release]
                21 = "src/main/java21"
                """);
        Path main = Files.createDirectories(lib.resolve("src/main/java/com/example"));
        Files.writeString(main.resolve("Greeting.java"), greetingSource("base"));
        Path overlay = Files.createDirectories(lib.resolve("src/main/java21/com/example"));
        Files.writeString(overlay.resolve("Greeting.java"), greetingSource("21"));
        Files.writeString(overlay.resolve("Only21.java"), """
                package com.example;

                import java.util.List;

                /** Reaches a Java 21 API and the base classes. */
                public final class Only21 {
                    private Only21() {}

                    /** The last of a sequenced list. */
                    public static int last() {
                        return List.of(1, 2).reversed().get(0) + Greeting.text().length();
                    }
                }
                """);
        Files.writeString(lib.resolve("src/main/java21/module-info.java"), """
                module com.example {
                    exports com.example;
                }
                """);
        return ws;
    }
}
