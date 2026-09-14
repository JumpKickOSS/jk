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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a workspace consumer compiles against its sibling's classes tree and is scheduled
 * as soon as that tree is whole, while everything that runs the sibling still reads its jar.
 *
 * <p>The edge a consumer's compile has to a sibling is compile-to-compile: {@code app}'s javac
 * reads {@code lib}'s {@code classes/main}, which exists once lib has compiled and copied its
 * resources, and nothing app compiles needs lib's jar. So app is admitted then — before lib
 * packages, tests or builds a native tail — and the two modules overlap where a jar-shaped edge
 * would serialize them. The jar is still what app's package and test steps read, so those wait
 * for lib to have written it, and a body-only edit in lib that leaves app's compile keyed the same
 * still reaches app's assembly as the new bytes.
 *
 * <p>Network: the Kotlin compiler for the mixed sibling comes from Maven Central into the cache
 * under {@code build/}, which persists across runs so repeats are warm.
 */
@Tag("integration")
class WorkspaceSiblingClassesE2eTest {

    /** How long the probe holds lib's jar back waiting for app's compile to begin. */
    private static final Duration ADMISSION_WINDOW = Duration.ofSeconds(60);

    @Test
    void a_consumer_is_admitted_on_the_siblings_classes_tree_and_packages_against_its_jar(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("sibling-classes-cache");
        Path ws = javaWorkspace(tmp);
        lock(ws, cache);
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));

        // lib's package-jar is held until app's compile has started. Admission on lib's jar would
        // never let app start, and the hold would expire with the flag still down.
        Probe first = new Probe(true);
        WorkspaceResult initial = build(ws, cache, first);
        assertThat(initial.errors()).isEmpty();
        assertThat(initial.success())
                .as("initial build; failed steps " + first.failed())
                .isTrue();
        assertThat(first.appCompiledBeforeLibPackaged())
                .as("app's compile is admitted on lib's classes tree, ahead of lib's jar")
                .isTrue();
        assertThat(first.label("app", TaskNames.COMPILE_JAVA)).startsWith("compiling");
        assertThat(nestedLibClass(app.assemblyJar()))
                .as("app's assembly nests lib's classes from lib's jar")
                .contains("lib-v1");

        // A body-only edit in lib: its bytes move, its API does not. app's compile is keyed on
        // the ABI of lib's classes tree and does not run; app's assembly still ships the new bodies.
        Files.writeString(ws.resolve("lib/src/com/example/Lib.java"), LIB_SOURCE.replace("lib-v1", "lib-v2"));
        Probe edited = new Probe(false);
        WorkspaceResult afterEdit = build(ws, cache, edited);
        assertThat(afterEdit.success())
                .as("after the edit; failed steps " + edited.failed())
                .isTrue();
        assertThat(edited.label("lib", TaskNames.COMPILE_JAVA)).startsWith("compiling");
        assertThat(edited.label("app", TaskNames.COMPILE_JAVA))
                .as("app's compile is answered without javac: lib's tree changed, its ABI did not")
                .doesNotStartWith("compiling");
        assertThat(nestedLibClass(app.assemblyJar()))
                .as("the package step read lib's new jar, not the tree app compiled against")
                .contains("lib-v2")
                .doesNotContain("lib-v1");
    }

    /**
     * A mixed Java+Kotlin sibling: the consumer compiles against the merged tree the classes
     * assembler produces, so a Java class of app can name lib's Java and Kotlin types alike.
     */
    @Test
    void a_java_consumer_compiles_against_a_mixed_siblings_merged_classes_tree(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("sibling-classes-cache");
        Path ws = mixedWorkspace(tmp);
        lock(ws, cache);
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));

        Probe probe = new Probe(false);
        WorkspaceResult result = build(ws, cache, probe);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success())
                .as("mixed sibling; failed steps " + probe.failed())
                .isTrue();
        assertThat(probe.label("app", TaskNames.COMPILE_JAVA)).startsWith("compiling");
        assertThat(app.classesDir().resolve("com/example/app/Main.class")).isRegularFile();
    }

    /**
     * After the whole {@code target/} is gone, the forecast prices app's compile as the build
     * keys it: lib's tree comes back from its compile record before app's compile keys on it, so
     * the forecast reads that record's token rather than the tree's absence, and both modules
     * restore instead of recompiling — {@code jk explain} and {@code jk build} agree.
     */
    @Test
    void a_wiped_workspace_forecasts_the_consumers_compile_as_the_build_keys_it(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("sibling-classes-cache");
        Path ws = javaWorkspace(tmp);
        lock(ws, cache);
        Probe first = new Probe(false);
        assertThat(build(ws, cache, first).success())
                .as("initial build; failed steps " + first.failed())
                .isTrue();

        PathUtil.deleteRecursively(ws.resolve("target"));

        List<TaskForecast.Module> plan = forecast(ws, cache);
        TaskForecast.Task libCompile = step(plan, "lib", TaskNames.COMPILE_MAIN);
        TaskForecast.Task appCompile = step(plan, "app", TaskNames.COMPILE_MAIN);
        assertThat(libCompile.cached())
                .as("lib's compile restores: " + libCompile)
                .isTrue();
        assertThat(appCompile.cached())
                .as("app's compile keys on lib's restored tree, not on its absence: " + appCompile)
                .isTrue();
        assertThat(step(plan, "app", TaskNames.PACKAGE_JAR).cached())
                .as("app's jar is keyed on the tree that comes back, too")
                .isTrue();

        Probe restored = new Probe(false);
        assertThat(build(ws, cache, restored).success())
                .as("restore build; failed steps " + restored.failed())
                .isTrue();
        assertThat(restored.label("lib", TaskNames.COMPILE_JAVA)).startsWith("cache hit");
        assertThat(restored.label("app", TaskNames.COMPILE_JAVA))
                .as("the build's key is the forecast's: a restore, not a compile")
                .startsWith("cache hit");
    }

    private static List<TaskForecast.Module> forecast(Path ws, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return TaskForecaster.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
    }

    private static TaskForecast.Module module(List<TaskForecast.Module> plan, String module) {
        return plan.stream()
                .filter(m -> m.dir().getFileName().toString().equals(module))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no forecast for " + module));
    }

    private static TaskForecast.Task step(List<TaskForecast.Module> plan, String module, String step) {
        TaskForecast.Module m = module(plan, module);
        return m.steps().stream()
                .filter(s -> s.name().equals(step))
                .findFirst()
                .orElseThrow(() -> new AssertionError(module + " forecasts no " + step + ": " + m.steps()));
    }

    private static WorkspaceResult build(Path ws, Path cache, Probe probe) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), probe);
    }

    /** The text of lib's class as nested in {@code jar}, for the string constants its bodies carry. */
    private static String nestedLibClass(Path jar) throws IOException {
        assertThat(jar).isRegularFile();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("com/example/Lib.class");
            assertThat(entry).as("lib's class inside " + jar).isNotNull();
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
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

    /**
     * Watches both modules. With {@code holdLibJar}, lib's package-jar is held at its start until
     * app's compile has started (or the window closes), and whether app got there first is recorded.
     */
    private static final class Probe implements WorkspaceBuildListener {
        private final boolean holdLibJar;
        private final CountDownLatch appCompileStarted = new CountDownLatch(1);
        private final AtomicBoolean appCompiledBeforeLibPackaged = new AtomicBoolean();
        private final Map<String, List<String>> failedByModule = new LinkedHashMap<>();
        private final Map<String, String> lastLabel = new LinkedHashMap<>();

        Probe(boolean holdLibJar) {
            this.holdLibJar = holdLibJar;
        }

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepStart(String step, @Nullable String group, int ticks) {
                    if (name.equals("app") && step.equals(TaskNames.COMPILE_JAVA)) appCompileStarted.countDown();
                    if (holdLibJar && name.equals("lib") && step.equals(TaskNames.PACKAGE_JAR)) {
                        try {
                            boolean started =
                                    appCompileStarted.await(ADMISSION_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
                            appCompiledBeforeLibPackaged.set(started);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                @Override
                public void label(String step, String label) {
                    synchronized (Probe.this) {
                        lastLabel.put(name + "/" + step, label);
                    }
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    if (status != TaskStatus.FAIL) return;
                    synchronized (Probe.this) {
                        failedByModule
                                .computeIfAbsent(name, k -> new ArrayList<>())
                                .add(step);
                    }
                }
            };
        }

        boolean appCompiledBeforeLibPackaged() {
            return appCompiledBeforeLibPackaged.get();
        }

        synchronized Map<String, List<String>> failed() {
            return Map.copyOf(failedByModule);
        }

        synchronized String label(String module, String step) {
            return lastLabel.getOrDefault(module + "/" + step, "");
        }
    }

    private static final String LIB_SOURCE = """
            package com.example;

            public final class Lib {
                private Lib() {}

                public static int twice(int n) {
                    return n * 2;
                }

                public static String version() {
                    return "lib-v1";
                }
            }
            """;

    /** lib is one Java class; app is an assembled application that uses it. */
    private static Path javaWorkspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        root(ws);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), LIB_SOURCE);
        app(ws, """
                package com.example.app;

                import com.example.Lib;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(Lib.version() + " " + Lib.twice(2));
                    }
                }
                """);
        return ws;
    }

    /** lib is Java plus Kotlin; app's Java names a type from each. */
    private static Path mixedWorkspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        root(ws);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), LIB_SOURCE);
        Files.writeString(lib.resolve("src/com/example/KLib.kt"), """
                package com.example

                object KLib {
                    fun thrice(n: Int): Int = n * 3
                }
                """);
        app(ws, """
                package com.example.app;

                import com.example.KLib;
                import com.example.Lib;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(Lib.twice(2) + KLib.INSTANCE.thrice(2));
                    }
                }
                """);
        return ws;
    }

    private static void root(Path ws) throws IOException {
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
    }

    private static void app(Path ws, String main) throws IOException {
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }

                [application]
                main     = "com.example.app.Main"
                assembly = true

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(app.resolve("src/com/example/app"));
        Files.writeString(src.resolve("Main.java"), main);
    }
}
