// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.BuildStamps;
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
import cc.jumpkick.task.FreshnessStamp;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end compile avoidance: a dependent's compile is keyed on what javac can see of its
 * classpath — the JVM ABI of each entry — not on the bytes.
 *
 * <p>{@code lib} is edited four ways and {@code app}, which compiles against lib's jar, answers
 * each through the real planner, compiler worker, action cache and workspace scheduler:
 *
 * <ul>
 * <li>a body-only edit: lib compiles and re-packages (the jar is full bytes), app's compile is up
 *     to date — no worker forks — and its action key is the one the first build stored;
 * <li>a resource-only edit: the same, through lib's re-packaged jar;
 * <li>a public method app never calls: app's key misses and its compile runs (Zinc decides how
 *     little);
 * <li>an inlined constant app uses: app's compile runs and the consumer's bytecode changes.
 * </ul>
 *
 * <p>Network: the junit launcher pin comes from Maven Central into the cache under {@code build/},
 * which persists across runs so repeats are warm.
 */
@Tag("integration")
class JavaAbiCompileAvoidanceE2eTest {

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

    /** Same API: a new body for {@code twice} and a private constant. */
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

    /** New API app does not use. */
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

    /** A changed inlined constant app copies into its own bytecode. */
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

    @Test
    void a_dependents_compile_follows_the_dependencys_api_not_its_bytes(@TempDir Path tmp) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "abi-avoidance-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);
        Path libSource = ws.resolve("lib/src/com/example/Lib.java");
        Path libResource = ws.resolve("lib/resources/lib.properties");
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));
        Path usesLib = app.classesDir().resolve("com/example/app/UsesLib.class");

        build(ws, cache, "initial");
        String initialKey = FreshnessStamp.stampedKey(app.classesDir(), BuildStamps.JAVA)
                .orElseThrow(() -> new AssertionError("app's stamp names the key of its first compile"));
        byte[] usesLibInitial = Files.readAllBytes(usesLib);

        // 1. Body-only edit: lib compiles and re-packages; app does not compile.
        Files.writeString(libSource, LIB_BODY_EDIT);
        Steps bodyEdit = build(ws, cache, "after lib's body-only edit");
        assertThat(bodyEdit.labels("lib", TaskNames.COMPILE_JAVA)).anyMatch(l -> l.startsWith("compiling "));
        assertThat(bodyEdit.labels("lib", TaskNames.PACKAGE_JAR))
                .as("the jar is keyed on full bytes, so a body-only change re-packages")
                .anyMatch(l -> l.startsWith("package "));
        assertNotCompiled(bodyEdit, "after lib's body-only edit");
        assertThat(forecast(ws, cache, "app", TaskNames.COMPILE_MAIN).cached())
                .as("jk explain agrees: app's compile is cached after a body-only upstream edit")
                .isTrue();

        // The same key as the first build's: with the stamp gone, the compile is an action-cache
        // hit on it, and the forecast prices it against that record.
        Files.delete(app.classesDir().resolve(BuildStamps.JAVA));
        TaskForecast.Task predicted = forecast(ws, cache, "app", TaskNames.COMPILE_MAIN);
        assertThat(predicted.cached()).isTrue();
        assertThat(predicted.key()).isEqualTo(initialKey.substring(0, 8));
        Steps forced = build(ws, cache, "app forced after the body-only edit", Set.of(ws.resolve("app")));
        assertThat(forced.labels("app", TaskNames.COMPILE_JAVA))
                .as("the key is unchanged: the compile is a hit on the first build's record")
                .anyMatch(l -> l.equals("cache hit " + initialKey.substring(0, 8)));
        assertThat(Files.readAllBytes(usesLib)).isEqualTo(usesLibInitial);

        // 2. Resource-only edit in lib: its jar changes again, app's compile still does not run.
        Files.writeString(libResource, "edition=second\n");
        Steps resourceEdit = build(ws, cache, "after lib's resource-only edit");
        assertThat(resourceEdit.labels("lib", TaskNames.PACKAGE_JAR)).anyMatch(l -> l.startsWith("package "));
        assertNotCompiled(resourceEdit, "after lib's resource-only edit");

        // 3. New public method app never calls: the ABI moved, so the key misses and the compile
        // runs; what Zinc recompiles is its business.
        Files.writeString(libSource, LIB_NEW_METHOD);
        Steps newMethod = build(ws, cache, "after lib gained a public method");
        assertThat(newMethod.labels("app", TaskNames.COMPILE_JAVA))
                .as("an API change is a compile input")
                .anyMatch(l -> l.startsWith("compiling "));
        assertThat(Files.readAllBytes(usesLib))
                .as("app's own bytecode is unchanged by an API it does not use")
                .isEqualTo(usesLibInitial);

        // 4. Inlined constant app uses: the compile runs and the consumer's bytecode changes.
        Files.writeString(libSource, LIB_CONSTANT_EDIT);
        Steps constant = build(ws, cache, "after lib's inlined constant changed");
        assertThat(constant.labels("app", TaskNames.COMPILE_JAVA)).anyMatch(l -> l.startsWith("compiling "));
        assertThat(Files.readAllBytes(usesLib))
                .as("javac copied the new constant into the consumer")
                .isNotEqualTo(usesLibInitial);
    }

    private static void assertNotCompiled(Steps steps, String when) {
        List<String> labels = steps.labels("app", TaskNames.COMPILE_JAVA);
        assertThat(labels).as("app compile labels " + when).noneMatch(l -> l.startsWith("compiling "));
        assertThat(labels)
                .as("app's compile is a stamp skip or an action-cache hit " + when)
                .anyMatch(l -> l.equals("up to date") || l.startsWith("cache hit "));
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
        // Members redirect to the root lock.
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

    /** lib exposes a method and an inlined constant; app uses both and has a class that uses neither. */
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
        Files.createDirectories(lib.resolve("resources"));
        Files.writeString(lib.resolve("resources/lib.properties"), "edition=first\n");

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(app.resolve("src/com/example/app"));
        Files.writeString(src.resolve("UsesLib.java"), """
                package com.example.app;

                import com.example.Lib;

                public final class UsesLib {
                    private UsesLib() {}

                    public static int four() {
                        return Lib.twice(2);
                    }

                    public static int scaled(int n) {
                        return n * Lib.FACTOR;
                    }
                }
                """);
        Files.writeString(src.resolve("Alone.java"), """
                package com.example.app;

                public final class Alone {
                    private Alone() {}

                    public static String name() {
                        return "alone";
                    }
                }
                """);
        return ws;
    }
}
