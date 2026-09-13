// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.wire.runtime.ModuleOutcome;
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
 * End-to-end: a Kotlin test module recompiles when a dependency's API moves.
 *
 * <p>The incremental Kotlin compile decides what to recompile from two things: which of its own
 * sources changed, and ABI snapshots of its classpath entries. A sibling module's jar is
 * rewritten at the same path by every build that touches it, so a snapshot that was reused
 * whenever one existed for that path would still describe the previous API; the
 * compile would see no source change and no classpath change, recompile nothing, and a test that
 * calls a method the dependency no longer has would keep its stale class. The build still fails
 * then — at run-tests, with a {@code NoSuchMethodError} — so the outcome alone cannot tell the
 * two apart; what this asserts is that the failure is the <em>compile</em>'s, on the test source
 * that makes the call. The unit test on the snapshot naming pins the mechanism; this proves the
 * effect it exists for, through the real planner, worker and workspace scheduler.
 *
 * <p>Network: junit and the Kotlin compiler come from Maven Central into the cache under
 * {@code build/}, which persists across runs so repeats are warm.
 */
@Tag("integration")
class KotlinDependencyApiChangeE2eTest {

    @Test
    void a_dependency_api_change_fails_the_dependent_modules_kotlin_test_compile(@TempDir Path tmp) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);

        WorkspaceResult green = build(ws, cache, new Steps());
        assertThat(green.errors()).isEmpty();
        assertThat(green.success())
                .as("lib and app build and app's test passes")
                .isTrue();

        // The compile classpath entry app's test compile snapshots: lib's jar, at the path every
        // build rewrites.
        Path libJar = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")))
                .mainJar();
        assertThat(libJar).isRegularFile();
        List<Path> before = snapshotsOf(libJar, cache);
        assertThat(before).as("one current snapshot of lib's jar").hasSize(1);

        // The API moves: the test's call no longer resolves. Nothing under app changes.
        Files.writeString(ws.resolve("lib/src/com/example/Lib.kt"), """
                package com.example

                object Lib {
                    fun twice(n: Int, times: Int): Int = n * times
                }
                """);

        Steps steps = new Steps();
        WorkspaceResult red = build(ws, cache, steps);

        assertThat(red.success())
                .as("app's test still calls twice(n): the incremental test compile must recompile it and fail")
                .isFalse();
        assertThat(outcome(red, "lib").success()).as("lib itself compiles").isTrue();
        assertThat(outcome(red, "app").success())
                .as("app fails on the changed call")
                .isFalse();
        assertThat(steps.failed("app"))
                .as("the failure is the test compile's, not a stale class blowing up under the test runner")
                .contains(TaskNames.COMPILE_TEST)
                .doesNotContain(TaskNames.RUN_TESTS);
        assertThat(steps.errors("app", TaskNames.COMPILE_TEST))
                .as("kotlinc names the test source that makes the call")
                .anyMatch(message -> message.contains("AppTest.kt"));
        List<Path> after = snapshotsOf(libJar, cache);
        assertThat(after).as("still one current snapshot of lib's jar").hasSize(1);
        assertThat(after.getFirst())
                .as("the snapshot describes the rewritten classes, not the previous API")
                .isNotEqualTo(before.getFirst());
    }

    /** The snapshot files the compiler worker holds for {@code entry}, by the entry's path prefix. */
    private static List<Path> snapshotsOf(Path entry, Path cache) throws IOException {
        Path dir = CacheTree.KOTLIN_CP_SNAPSHOTS.under(cache);
        String prefix = Hashing.sha256Hex(entry.toAbsolutePath().toString()) + "-";
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix))
                    .filter(f -> f.getFileName().toString().endsWith(".snapshot"))
                    .sorted()
                    .toList();
        }
    }

    private static ModuleOutcome outcome(WorkspaceResult result, String module) {
        return result.modules().stream()
                .filter(m -> m.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + module + " in " + result.modules()));
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

    private static WorkspaceResult build(Path ws, Path cache, Steps steps) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false), steps);
    }

    /** Which steps failed in which module, and what each step reported. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, List<String>> failedByModule = new LinkedHashMap<>();
        private final Map<String, List<String>> errorsByModuleStep = new LinkedHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void error(String step, String code, String message) {
                    synchronized (Steps.this) {
                        errorsByModuleStep
                                .computeIfAbsent(name + "/" + step, k -> new ArrayList<>())
                                .add(message);
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

        synchronized List<String> failed(String module) {
            return List.copyOf(failedByModule.getOrDefault(module, List.of()));
        }

        synchronized List<String> errors(String module, String step) {
            return List.copyOf(errorsByModuleStep.getOrDefault(module + "/" + step, List.of()));
        }
    }

    /**
     * lib is Kotlin with one public function; app's main sources do not use it, so after the API
     * change only app's test compile has a reason to fail.
     */
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
                kotlin  = "^2.4.10"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.kt"), """
                package com.example

                object Lib {
                    fun twice(n: Int): Int = n * 2
                }
                """);

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.kt"), """
                package com.example

                object App {
                    const val NAME = "app"
                }
                """);
        Files.createDirectories(app.resolve("test/src/com/example"));
        Files.writeString(app.resolve("test/src/com/example/AppTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class AppTest {
                    @Test
                    fun twice_comes_from_lib() {
                        assertEquals(4, Lib.twice(2))
                    }
                }
                """);
        return ws;
    }
}
