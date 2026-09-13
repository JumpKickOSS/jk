// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a module whose inputs are unchanged but whose classes tree is missing or short is
 * brought back to the tree its <em>current</em> inputs compile to, through the real preflight,
 * planner, action cache and workspace scheduler.
 *
 * <p>Network: the junit launcher pin comes from Maven Central into the cache under {@code build/},
 * which persists across runs so repeats are warm.
 */
@Tag("integration")
class ClassesTreeRestoreE2eTest {

    private static final String REVISION_A = """
            package com.example;

            public final class One {
                private One() {}

                public static String revision() {
                    return "revision-A";
                }
            }
            """;

    private static final String REVISION_B = """
            package com.example;

            public final class One {
                private One() {}

                public static String revision() {
                    return "revision-B";
                }
            }
            """;

    /**
     * After an edit, a build, a revert and a build, the most recent compile record written for the
     * module is the edited build's while the current key is the original's. An emptied tree comes
     * back from the record the current key names, never from the last one written.
     */
    @Test
    void an_emptied_tree_is_refilled_with_the_current_keys_bytecode_after_a_revert(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("classes-tree-restore-cache");
        Path ws = workspace(tmp, REVISION_A);
        lock(ws, cache);
        Path source = ws.resolve("lib/src/com/example/One.java");
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));
        Path one = lib.classesDir().resolve("com/example/One.class");

        build(ws, cache, "revision A");
        assertThat(constantPool(one)).contains("revision-A");

        Files.writeString(source, REVISION_B);
        build(ws, cache, "revision B");
        assertThat(constantPool(one)).contains("revision-B");

        Files.writeString(source, REVISION_A);
        build(ws, cache, "revision A again");
        assertThat(constantPool(one))
                .as("the revert build brings A's bytecode back")
                .contains("revision-A");

        PathUtil.deleteRecursively(lib.classesDir());
        build(ws, cache, "after the classes tree was wiped");
        assertThat(constantPool(one))
                .as("the wiped tree is refilled from the record the current key names")
                .contains("revision-A")
                .doesNotContain("revision-B");
        assertThat(constantPool(classFromJar(lib.mainJar(), "com/example/One.class")))
                .as("the jar follows the tree")
                .contains("revision-A");
    }

    /**
     * A tree that lost a class its compile record owns, with every key still hitting, is made whole
     * on the next plain build; the forecast names it a restore, not a rebuild.
     */
    @Test
    void a_partial_classes_tree_is_made_whole_without_redo(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("classes-tree-partial-cache");
        Path ws = workspace(tmp, REVISION_A);
        lock(ws, cache);
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));
        Set<String> whole = Set.of("com/example/One.class", "com/example/Two.class");

        build(ws, cache, "initial");
        assertThat(classesUnder(lib.classesDir())).isEqualTo(whole);
        assertThat(classesIn(lib.mainJar())).isEqualTo(whole);

        Files.delete(lib.classesDir().resolve("com/example/Two.class"));
        TaskForecast.Module forecast = forecast(ws, cache, "lib");
        assertThat(forecast.steps())
                .as("every step still hits; the only work is the restore, and it says why")
                .anyMatch(s ->
                        TaskNames.RESTORE_OUTPUTS.equals(s.name()) && s.text().contains("incomplete"))
                .noneMatch(s -> !s.cached() && !TaskNames.RESTORE_OUTPUTS.equals(s.name()));

        build(ws, cache, "after a class was deleted from the tree");
        assertThat(classesUnder(lib.classesDir()))
                .as("tree after the plain build")
                .isEqualTo(whole);
        assertThat(classesIn(lib.mainJar())).as("jar after the plain build").isEqualTo(whole);
    }

    private static TaskForecast.Module forecast(Path ws, Path cache, String module) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return TaskForecaster.of(graph, JkStores.cacheCas(cache), actionCache, cache, true).stream()
                .filter(m -> m.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> classesUnder(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (f.toString().endsWith(".class"))
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

    private static String constantPool(Path classFile) throws IOException {
        return constantPool(Files.readAllBytes(classFile));
    }

    private static String constantPool(byte[] classBytes) {
        return new String(classBytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] classFromJar(Path jar, String entry) throws IOException {
        assertThat(jar).isRegularFile();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var e = zip.getEntry(entry);
            assertThat(e).as(entry + " in " + jar).isNotNull();
            try (var in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    static void build(Path ws, Path cache, String what) {
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
    }

    static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("lib/jk-lock.toml"));
    }

    static Path workspace(Path tmp, String oneSource) throws IOException {
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

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(src.resolve("One.java"), oneSource);
        Files.writeString(src.resolve("Two.java"), """
                package com.example;

                public final class Two {
                    private Two() {}

                    public static int answer() {
                        return 42;
                    }
                }
                """);
        return ws;
    }
}
