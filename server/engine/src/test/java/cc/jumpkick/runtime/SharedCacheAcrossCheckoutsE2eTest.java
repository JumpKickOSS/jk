// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionKey;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: two checkouts of one workspace share one action cache. The second checkout of an
 * identical tree restores every compile, package and test step from the first's records; an edit
 * in one checkout reruns only its dependents there and leaves the other checkout's hits intact.
 *
 * <p>What this pins is the tier rule of the cache: a key and its task pointer name content and a
 * project-relative output, never the checkout, while the incremental compiler state stays with
 * the checkout whose absolute paths it holds.
 *
 * <p>Network: the JUnit pins come from Maven Central into the cache under {@code build/}, which
 * persists across runs so repeats are warm.
 */
@Tag("integration")
class SharedCacheAcrossCheckoutsE2eTest {

    private static final String LIB_SOURCE = """
            package com.example;

            public final class Lib {
                private Lib() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    /** Same API, new bytecode. */
    private static final String LIB_BODY_EDIT = """
            package com.example;

            public final class Lib {
                private static final long EDIT_STAMP = 1L;

                private Lib() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    /** A new public method: the ABI moves. */
    private static final String LIB_API_EDIT = """
            package com.example;

            public final class Lib {
                private Lib() {}

                public static int twice(int n) {
                    return n * 2;
                }

                public static int thrice(int n) {
                    return n * 3;
                }
            }
            """;

    @Test
    void a_second_checkout_restores_everything_and_edits_stay_in_their_checkout(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("shared-cache-checkouts");
        Path a = workspace(tmp.resolve("checkout-a"));
        lock(a, cache);
        Path b = copyCheckout(a, tmp.resolve("elsewhere/checkout-b"));

        Steps first = build(a, cache, "first build of A");
        assertThat(compiled(first.labels("lib", TaskNames.COMPILE_JAVA)))
                .as("A compiles lib")
                .isTrue();
        Set<String> keysAfterA = children(ActionTree.KEYS.under(CacheTree.ACTIONS.under(cache)));
        Set<String> pointersAfterA = children(ActionTree.TASKS.under(CacheTree.ACTIONS.under(cache)));

        // B is byte-identical to A at another absolute path: every step restores or replays.
        Steps second = build(b, cache, "first build of B");
        for (String module : List.of("lib", "app")) {
            assertThat(restored(second.labels(module, TaskNames.COMPILE_JAVA)))
                    .as(module + " compile-main in B: " + second.labels(module, TaskNames.COMPILE_JAVA))
                    .isTrue();
            assertThat(second.labels(module, TaskNames.PACKAGE_JAR))
                    .as(module + " package-jar in B")
                    .anyMatch(l -> l.endsWith("up-to-date"))
                    .noneMatch(l -> l.startsWith("package "));
        }
        assertThat(second.labels("app", TaskNames.RUN_TESTS))
                .as("app run-tests in B")
                .contains(TaskNames.TESTS_UP_TO_DATE);
        assertThat(second.compiledSteps()).as("nothing compiles in B").isEmpty();
        assertThat(children(ActionTree.KEYS.under(CacheTree.ACTIONS.under(cache))))
                .as("B wrote no key A had not")
                .isEqualTo(keysAfterA);
        assertThat(children(ActionTree.TASKS.under(CacheTree.ACTIONS.under(cache))))
                .as("B's task pointers are A's")
                .isEqualTo(pointersAfterA);
        Path incremental = ActionTree.INCREMENTAL_JAVA.under(CacheTree.ACTIONS.under(cache));
        assertThat(ActionKey.stateDir(incremental, TaskNames.COMPILE_MAIN, classesDir(a, "lib")))
                .as("A's incremental state")
                .isDirectory();
        assertThat(ActionKey.stateDir(incremental, TaskNames.COMPILE_MAIN, classesDir(b, "lib")))
                .as("B has no incremental state of its own until it compiles")
                .isNotEqualTo(ActionKey.stateDir(incremental, TaskNames.COMPILE_MAIN, classesDir(a, "lib")));

        // A body-only edit in B: lib compiles and packages there; app's compile hits on lib's ABI;
        // app's tests rerun on the changed runtime classpath. A is untouched.
        Files.writeString(b.resolve("lib/src/com/example/Lib.java"), LIB_BODY_EDIT);
        Steps bodyEdit = build(b, cache, "body-only edit in B");
        assertThat(compiled(bodyEdit.labels("lib", TaskNames.COMPILE_JAVA)))
                .as("B compiles lib")
                .isTrue();
        assertThat(bodyEdit.labels("lib", TaskNames.PACKAGE_JAR)).anyMatch(l -> l.startsWith("package "));
        assertThat(compiled(bodyEdit.labels("app", TaskNames.COMPILE_JAVA)))
                .as("app compile is keyed on lib's ABI: " + bodyEdit.labels("app", TaskNames.COMPILE_JAVA))
                .isFalse();
        assertThat(bodyEdit.labels("app", TaskNames.RUN_TESTS))
                .as("app tests see lib's new bytes on their classpath")
                .doesNotContain(TaskNames.TESTS_UP_TO_DATE);
        Steps aAfterBodyEdit = build(a, cache, "A after B's body edit");
        assertThat(aAfterBodyEdit.compiledSteps()).as("A still hits").isEmpty();
        // The forecast may leave an all-cached module unscheduled; a scheduled one replays its stamp.
        List<String> aTests = aAfterBodyEdit.labels("app", TaskNames.RUN_TESTS);
        assertThat(aTests.isEmpty() || aTests.contains(TaskNames.TESTS_UP_TO_DATE))
                .as("A's tests do not rerun: " + aTests)
                .isTrue();

        // An API edit in B: app misses there. A is still untouched.
        Files.writeString(b.resolve("lib/src/com/example/Lib.java"), LIB_API_EDIT);
        Steps apiEdit = build(b, cache, "API edit in B");
        assertThat(compiled(apiEdit.labels("app", TaskNames.COMPILE_JAVA)))
                .as("a new public method moves app's key")
                .isTrue();
        Steps aAfterApiEdit = build(a, cache, "A after B's API edit");
        assertThat(aAfterApiEdit.compiledSteps()).isEmpty();

        // Reverting B returns it to keys both checkouts hold.
        Files.writeString(b.resolve("lib/src/com/example/Lib.java"), LIB_SOURCE);
        Steps reverted = build(b, cache, "B reverted");
        assertThat(reverted.compiledSteps()).isEmpty();
    }

    /** A compile step labels "compiling N sources" before it looks the key up; a restore adds "cache hit". */
    private static boolean compiled(List<String> labels) {
        return labels.stream().anyMatch(l -> l.startsWith("compiling")) && !restored(labels);
    }

    private static boolean restored(List<String> labels) {
        return labels.stream().anyMatch(l -> l.startsWith("cache hit"));
    }

    private static Path classesDir(Path ws, String module) {
        return ws.resolve("target").resolve(module).resolve("classes/main");
    }

    private static Set<String> children(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> out.add(f.getFileName().toString()));
        }
        return out;
    }

    /** Everything but {@code target/}, so B starts with sources, manifests and the committed lock. */
    private static Path copyCheckout(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path rel = from.relativize(f);
                if (rel.getNameCount() > 0 && rel.getName(0).toString().equals("target")) continue;
                Path target = to.resolve(rel);
                if (Files.isDirectory(f)) Files.createDirectories(target);
                else Files.copy(f, target);
            }
        }
        return to;
    }

    private static Steps build(Path ws, Path cache, String what) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false), steps);
        assertThat(result.errors()).as("errors, " + what).isEmpty();
        assertThat(result.success())
                .as("success, " + what + "; failed steps " + steps.failed())
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

    /** Every label each step said, per module. */
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

        /** The module/step pairs whose compiler ran. */
        synchronized List<String> compiledSteps() {
            List<String> out = new ArrayList<>();
            labelsByModuleStep.forEach((step, labels) -> {
                if (compiled(labels)) out.add(step);
            });
            return out;
        }
    }

    /** lib is one class; app uses it and has one JUnit test against it. */
    private static Path workspace(Path ws) throws IOException {
        Files.createDirectories(ws);
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
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), LIB_SOURCE);

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example/app"));
        Files.writeString(app.resolve("src/com/example/app/UsesLib.java"), """
                package com.example.app;

                import com.example.Lib;

                public final class UsesLib {
                    private UsesLib() {}

                    public static int four() {
                        return Lib.twice(2);
                    }
                }
                """);
        Files.createDirectories(app.resolve("test/src/com/example/app"));
        Files.writeString(app.resolve("test/src/com/example/app/UsesLibTest.java"), """
                package com.example.app;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class UsesLibTest {
                    @Test
                    void four() {
                        assertEquals(4, UsesLib.four());
                    }
                }
                """);
        return ws;
    }
}
