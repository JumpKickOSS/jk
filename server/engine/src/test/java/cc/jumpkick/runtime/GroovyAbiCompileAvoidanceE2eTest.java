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
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end compile avoidance for a Groovy consumer: {@code app} (Groovy only) compiles against
 * the jar of {@code lib} (Java), and its compile-groovy step — a full groovyc whenever it runs —
 * is keyed on lib's JVM ABI. A body-only edit in lib leaves app up to date; a new public method
 * app then starts calling, and a changed constant value app reads, each run groovyc and the new
 * API is visible to it.
 *
 * <p>Network: the Groovy closure and the junit launcher pin come from Maven Central into the
 * cache under {@code build/}, which persists across runs so repeats are warm.
 */
@Tag("slow")
class GroovyAbiCompileAvoidanceE2eTest {

    private static final String LIB = """
            package com.example;

            public final class Lib {
                public static final int FACTOR = 2;

                private Lib() {}

                public static int twice(int n) {
                    return n * FACTOR;
                }
            }
            """;

    private static final String LIB_BODY_EDIT = """
            package com.example;

            public final class Lib {
                public static final int FACTOR = 2;
                private static final long EDIT_STAMP = 1L;

                private Lib() {}

                public static int twice(int n) {
                    return n + n;
                }
            }
            """;

    private static final String LIB_NEW_METHOD = """
            package com.example;

            public final class Lib {
                public static final int FACTOR = 2;

                private Lib() {}

                public static int twice(int n) {
                    return n + n;
                }

                public static int thrice(int n) {
                    return n * 3;
                }
            }
            """;

    private static final String LIB_CONSTANT_EDIT = """
            package com.example;

            public final class Lib {
                public static final int FACTOR = 3;

                private Lib() {}

                public static int twice(int n) {
                    return n + n;
                }

                public static int thrice(int n) {
                    return n * 3;
                }
            }
            """;

    private static final String APP = """
            package com.example.app

            import com.example.Lib
            import groovy.transform.CompileStatic

            @CompileStatic
            class UsesLib {
                static int four() { Lib.twice(2) }
                static int scaled(int n) { n * Lib.FACTOR }
            }
            """;

    /** Calls the method lib gains in {@link #LIB_NEW_METHOD}: compiles only against the new API. */
    private static final String APP_USES_NEW_METHOD = """
            package com.example.app

            import com.example.Lib
            import groovy.transform.CompileStatic

            @CompileStatic
            class UsesLib {
                static int four() { Lib.twice(2) }
                static int nine() { Lib.thrice(3) }
                static int scaled(int n) { n * Lib.FACTOR }
            }
            """;

    @Test
    void a_groovy_consumers_compile_follows_the_java_dependencys_api(@TempDir Path tmp) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "groovy-abi-avoidance-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);
        Path libSource = ws.resolve("lib/src/com/example/Lib.java");
        Path appSource = ws.resolve("app/src/com/example/app/UsesLib.groovy");
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));
        Path usesLib = app.classesDir().resolve("com/example/app/UsesLib.class");

        build(ws, cache, "initial");
        byte[] usesLibInitial = Files.readAllBytes(usesLib);

        // Body-only edit: lib compiles and re-packages; app's groovyc does not run.
        Files.writeString(libSource, LIB_BODY_EDIT);
        Steps bodyEdit = build(ws, cache, "after lib's body-only edit");
        assertThat(bodyEdit.labels("lib", TaskNames.PACKAGE_JAR)).anyMatch(l -> l.startsWith("package "));
        List<String> labels = bodyEdit.labels("app", TaskNames.COMPILE_GROOVY);
        assertThat(labels).noneMatch(l -> l.startsWith("compiling "));
        assertThat(labels)
                .as("app's groovyc is a stamp skip or an action-cache hit, never a full compile")
                .anyMatch(l -> l.equals("up to date") || l.startsWith("cache hit "));
        assertThat(Files.readAllBytes(usesLib)).isEqualTo(usesLibInitial);

        // New API, and app uses it: the key misses, groovyc runs and resolves the new method.
        Files.writeString(libSource, LIB_NEW_METHOD);
        Files.writeString(appSource, APP_USES_NEW_METHOD);
        Steps newMethod = build(ws, cache, "after lib gained a method app calls");
        assertThat(newMethod.labels("app", TaskNames.COMPILE_GROOVY)).anyMatch(l -> l.startsWith("compiling "));
        byte[] usesLibWithNine = Files.readAllBytes(usesLib);
        assertThat(usesLibWithNine).isNotEqualTo(usesLibInitial);

        // Constant value: app's own source is untouched, yet its compile runs — a constant's value
        // is part of the API token, whether or not this compiler copies it into the consumer.
        Files.writeString(libSource, LIB_CONSTANT_EDIT);
        Steps constant = build(ws, cache, "after lib's constant changed");
        assertThat(constant.labels("app", TaskNames.COMPILE_GROOVY)).anyMatch(l -> l.startsWith("compiling "));
    }

    private static Steps build(Path ws, Path cache, String what) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), steps);
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success())
                .as("success, build " + what + "; failed steps " + steps.failed())
                .isTrue();
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

    /** Which steps failed in which module, and what each step said about itself. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, List<String>> failedByModule = new LinkedHashMap<>();
        private final Map<String, List<String>> labelsByModuleStep = new LinkedHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void label(String step, String label) {
                    synchronized (Steps.this) {
                        labelsByModuleStep
                                .computeIfAbsent(name + "/" + step, k -> new ArrayList<>())
                                .add(label);
                    }
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    if (status != TaskStatus.FAIL) return;
                    synchronized (Steps.this) {
                        failedByModule
                                .computeIfAbsent(name, k -> new ArrayList<>())
                                .add(step);
                    }
                }
            };
        }

        synchronized Map<String, List<String>> failed() {
            return Map.copyOf(failedByModule);
        }

        synchronized List<String> labels(String module, String step) {
            return List.copyOf(labelsByModuleStep.getOrDefault(module + "/" + step, List.of()));
        }
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

        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), LIB);

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                groovy  = "5.0.4"

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example/app"));
        Files.writeString(app.resolve("src/com/example/app/UsesLib.groovy"), APP);
        return ws;
    }
}
