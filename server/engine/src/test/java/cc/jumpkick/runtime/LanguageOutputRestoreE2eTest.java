// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classes tree a jar is packed from merges javac's output with each other language's. The
 * javac record must not own the merged classes — a restore of it would lay a dropped Kotlin
 * declaration back down — and the merged tree must be held against the Kotlin record, or a
 * partly wiped Kotlin-only tree stays "up to date" with its jar short of the same classes.
 *
 * <p>Network: the Kotlin compiler comes from Maven Central into a cache under {@code target/},
 * which persists across runs so repeats are warm.
 */
@Tag("integration")
class LanguageOutputRestoreE2eTest {

    private static final String UTIL = """
            package com.example;

            public final class Util {
                private Util() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    private static final String TWO_CLASSES = """
            package com.example

            class A {
                fun two() = Util.twice(1)
            }

            class B {
                fun four() = Util.twice(2)
            }
            """;

    private static final String ONE_CLASS = """
            package com.example

            class A {
                fun two() = Util.twice(1)
            }
            """;

    @Test
    void a_dropped_kotlin_declaration_does_not_return_through_a_javac_restore(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");
        Path ws = workspace(tmp, "app", true);
        Path app = ws.resolve("app");
        BuildLayout layout = layout(ws, "app");
        lock(ws, cache);

        Labels first = new Labels();
        assertBuilt(build(ws, cache, first), first, "first build");
        assertThat(jarEntries(layout.mainJar())).contains("com/example/A.class", "com/example/B.class");

        // A Java edit: javac re-runs in a tree that already holds the merged Kotlin classes.
        Files.writeString(app.resolve("src/com/example/Util.java"), UTIL.replace("n * 2", "n + n"));
        Labels edited = new Labels();
        assertBuilt(build(ws, cache, edited), edited, "build after the Java edit");

        // B leaves K.kt; kotlinc prunes it and the merge takes it out of the tree.
        Files.writeString(app.resolve("src/com/example/K.kt"), ONE_CLASS);
        Labels dropped = new Labels();
        assertBuilt(build(ws, cache, dropped), dropped, "build after the declaration was dropped");
        assertThat(jarEntries(layout.mainJar())).doesNotContain("com/example/B.class");

        // jk clean, then a build every record of which hits.
        PathUtil.deleteRecursively(app.resolve("target"));
        Labels restored = new Labels();
        assertBuilt(build(ws, cache, restored), restored, "build after clean");
        assertThat(layout.classesDir().resolve("com/example/B.class"))
                .as("the javac restore owns javac's classes only")
                .doesNotExist();
        assertThat(jarEntries(layout.mainJar()))
                .contains("com/example/A.class", "com/example/Util.class")
                .doesNotContain("com/example/B.class");
    }

    @Test
    void a_partly_wiped_kotlin_only_tree_is_restored_on_the_next_build(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");
        Path ws = workspace(tmp, "lib", false);
        BuildLayout layout = layout(ws, "lib");
        lock(ws, cache);

        Labels first = new Labels();
        assertBuilt(build(ws, cache, first), first, "first build");
        Path b = layout.classesDir().resolve("com/example/B.class");
        assertThat(b).isRegularFile();
        Labels settled = new Labels();
        assertBuilt(build(ws, cache, settled), settled, "second build");
        assertThat(settled.label("lib", TaskNames.COMPILE_KOTLIN))
                .as("a whole tree under a fresh stamp is up to date")
                .isIn("up to date", "");

        Files.delete(b);
        Labels restored = new Labels();
        assertBuilt(build(ws, cache, restored), restored, "build after the partial wipe");
        assertThat(restored.label("lib", TaskNames.COMPILE_KOTLIN))
                .as("the stamp is fresh but the tree lacks a class the record owns")
                .startsWith("cache hit");
        assertThat(b).as("the record's tree is restored").isRegularFile();
        assertThat(jarEntries(layout.mainJar())).contains("com/example/A.class", "com/example/B.class");
    }

    // ---- fixture -----------------------------------------------------------------------------

    /** One Kotlin module, with a Java class beside it when {@code mixed}; {@code K.kt} declares A and B. */
    private static Path workspace(Path tmp, String module, boolean mixed) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["%s"]
                """.formatted(module));
        Path dir = Files.createDirectories(ws.resolve(module));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(module));
        Files.createDirectories(dir.resolve("src/com/example"));
        if (mixed) {
            Files.writeString(dir.resolve("src/com/example/Util.java"), UTIL);
            Files.writeString(dir.resolve("src/com/example/K.kt"), TWO_CLASSES);
        } else {
            Files.writeString(
                    dir.resolve("src/com/example/K.kt"),
                    TWO_CLASSES.replace("Util.twice(1)", "2").replace("Util.twice(2)", "4"));
        }
        return ws;
    }

    private static BuildLayout layout(Path ws, String module) throws IOException {
        return BuildLayout.of(
                ws, ws.resolve(module), JkBuildParser.parse(ws.resolve(module).resolve("jk.toml")));
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        for (String member : root.workspaceModules()) {
            Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(member).resolve("jk-lock.toml"));
        }
    }

    private static WorkspaceResult build(Path ws, Path cache, Labels labels) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 1, null, false, false), labels);
    }

    private static void assertBuilt(WorkspaceResult result, Labels labels, String what) {
        assertThat(result.success())
                .as(what + ": " + result.errors() + " " + labels.failures())
                .isTrue();
    }

    private static Set<String> jarEntries(Path jar) throws IOException {
        assertThat(jar).as("the main jar").isRegularFile();
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    /** The last label each step of each module reported, and every error and output line. */
    private static final class Labels implements WorkspaceBuildListener {
        private final Map<String, String> last = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void label(String step, String label) {
                    synchronized (Labels.this) {
                        last.put(name + "/" + step, label);
                    }
                }

                @Override
                public void error(String step, String code, String message) {
                    synchronized (Labels.this) {
                        failures.add(name + "/" + step + " [" + code + "] " + message);
                    }
                }

                @Override
                public void output(String step, String line) {
                    synchronized (Labels.this) {
                        failures.add(name + "/" + step + ": " + line);
                    }
                }
            };
        }

        synchronized String label(String module, String step) {
            return last.getOrDefault(module + "/" + step, "");
        }

        synchronized List<String> failures() {
            return List.copyOf(failures);
        }
    }
}
