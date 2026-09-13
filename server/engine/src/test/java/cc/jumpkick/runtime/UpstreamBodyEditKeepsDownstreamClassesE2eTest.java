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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a dependency's body-only edit, and the revert that returns it to a cached key, leave
 * the dependent module's {@code classes/main} whole.
 *
 * <p>The dependent's compile sees the upstream change only through its classpath. Its own sources
 * are untouched, so the freshness stamp reads stale (the upstream tree's mtime moved), the action
 * key misses (the classpath fingerprint is content-based) and Zinc recompiles the sources that
 * reference the upstream class while the rest of the tree stays as the previous compile left it.
 * Reverting the edit returns both modules to keys the action cache already holds, and a hit
 * restores by pruning every file the record does not own before copying the record's outputs
 * back. Each of those steps is a place where a partial tree — a compile that touched a subset, a
 * record that lists a subset — becomes the module's whole output: the classes that are missing
 * from {@code classes/main} are missing from the jar, and the next module to compile against it
 * fails with {@code cannot find symbol} on a class nobody edited.
 *
 * <p>What this asserts is the invariant every one of those paths must keep: after every build in
 * the sequence, the dependent's classes tree and jar hold exactly the classes its sources compile
 * to, through the real planner, the compiler worker, the action cache and the workspace scheduler.
 *
 * <p>Network: the junit launcher pin comes from Maven Central into the cache under {@code build/},
 * which persists across runs so repeats are warm.
 */
@Tag("integration")
class UpstreamBodyEditKeepsDownstreamClassesE2eTest {

    private static final String LIB_SOURCE = """
            package com.example;

            public final class Lib {
                private Lib() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    /** The same class with a private constant added: new bytecode, same API. */
    private static final String LIB_SOURCE_EDITED = """
            package com.example;

            public final class Lib {
                private static final long EDIT_STAMP = 1L;

                private Lib() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    /** Every class app's sources compile to: users of Lib and classes that never mention it. */
    private static final Set<String> APP_CLASSES = Set.of(
            "com/example/app/UsesLib.class",
            "com/example/app/UsesLib$Later.class",
            "com/example/app/Alone.class",
            "com/example/app/Alone$Nested.class",
            "com/example/app/Other.class");

    @Test
    void a_body_only_upstream_edit_and_its_revert_keep_every_downstream_class(@TempDir Path tmp) throws Exception {
        Path cache = Path.of(System.getProperty("user.dir"), "build", "upstream-body-edit-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);
        Path libSource = ws.resolve("lib/src/com/example/Lib.java");
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));

        build(ws, cache, "initial");
        assertThat(classesUnder(app.classesDir())).as("initial classes tree").isEqualTo(APP_CLASSES);
        assertThat(classesIn(app.mainJar())).as("initial jar").isEqualTo(APP_CLASSES);

        // Upstream body-only edit: lib's bytecode changes, its API does not; nothing under app moves.
        Files.writeString(libSource, LIB_SOURCE_EDITED);
        Steps edited = build(ws, cache, "after the upstream edit");
        assertThat(edited.labels("app", TaskNames.COMPILE_JAVA))
                .as("app's compile sees lib's new bytecode on its classpath and compiles, not a stamp skip or a hit")
                .anyMatch(label -> label.startsWith("compiling "))
                .noneMatch(label -> label.startsWith("cache hit"));
        assertThat(classesUnder(app.classesDir()))
                .as("classes tree after app recompiled against the edited lib")
                .isEqualTo(APP_CLASSES);
        assertThat(classesIn(app.mainJar())).as("jar after the upstream edit").isEqualTo(APP_CLASSES);

        // Revert: both modules return to keys the action cache already holds. lib restores; app
        // forecasts every step cached against the tree on disk and is not scheduled at all.
        Files.writeString(libSource, LIB_SOURCE);
        build(ws, cache, "after the revert");
        assertThat(classesUnder(app.classesDir()))
                .as("classes tree after the revert")
                .isEqualTo(APP_CLASSES);
        assertThat(classesIn(app.mainJar())).as("jar after the revert").isEqualTo(APP_CLASSES);

        // Scheduled by hand, app's stamp reads stale (lib's restore rewrote Lib.class) and its key
        // is the initial build's: the classes come back through the action cache's restore, which
        // prunes whatever the record does not own before it copies.
        Steps restored = build(ws, cache, "app forced after the revert", Set.of(ws.resolve("app")));
        assertThat(restored.labels("app", TaskNames.COMPILE_JAVA))
                .as("app's compile is a cache restore, not a stamp skip or a recompile")
                .anyMatch(label -> label.startsWith("cache hit"));
        assertThat(classesUnder(app.classesDir()))
                .as("classes tree restored for a key the cache already held")
                .isEqualTo(APP_CLASSES);
        assertThat(classesIn(app.mainJar())).as("jar after the restore").isEqualTo(APP_CLASSES);

        // A second edit-and-revert round trip lands on the same two keys again.
        Files.writeString(libSource, LIB_SOURCE_EDITED);
        build(ws, cache, "second upstream edit");
        Files.writeString(libSource, LIB_SOURCE);
        build(ws, cache, "second revert");
        assertThat(classesUnder(app.classesDir()))
                .as("classes tree after the second round trip")
                .isEqualTo(APP_CLASSES);
        assertThat(classesIn(app.mainJar()))
                .as("jar after the second round trip")
                .isEqualTo(APP_CLASSES);

        build(ws, cache, "no-op");
        assertThat(classesUnder(app.classesDir()))
                .as("classes tree after a no-op build")
                .isEqualTo(APP_CLASSES);
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

    /** Relative paths of every {@code .class} under {@code dir}, in tree order. */
    private static Set<String> classesUnder(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.toString().endsWith(".class")) continue;
                out.add(dir.relativize(f).toString().replace('\\', '/'));
            }
        }
        return out;
    }

    private static Set<String> classesIn(Path jar) throws IOException {
        Set<String> out = new TreeSet<>();
        assertThat(jar).isRegularFile();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).forEach(out::add);
        }
        return out;
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

    /**
     * lib is one Java class; app depends on it with sources that use it, sources that do not, and
     * nested classes on both sides, so a tree that lost either kind of class is visible.
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

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

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

                    public static final class Later {
                        public int six() {
                            return Lib.twice(3);
                        }
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

                    public static final class Nested {
                        public String inner() {
                            return "nested";
                        }
                    }
                }
                """);
        Files.writeString(src.resolve("Other.java"), """
                package com.example.app;

                public final class Other {
                    private Other() {}

                    public static int answer() {
                        return 42;
                    }
                }
                """);
        return ws;
    }
}
