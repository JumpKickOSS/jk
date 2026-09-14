// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two modules laid out the way a workspace lays them out — {@code target/<module>/classes/main}
 * and {@code target/<module>/lib/<module>.jar}, every compile's state under one incremental root
 * keyed by its output dir — compiled through {@link JavaCompile} and the Zinc worker. The consumer
 * is handed the producer's analysis without either test or planner naming it.
 */
@Tag("integration")
class JavaProducerAnalysisCompileTest {

    private static final String T1 = "package lib; public class T { public int a() { return 1; } }";
    private static final String T2 =
            "package lib; public class T { public int a() { return 1; } public int b() { return 2; } }";
    private static final String U = "package lib; public class U { public int u() { return 3; } }";
    private static final String A = "package app; public class A { public int f() { return new lib.T().a(); } }";
    private static final String B = "package app; public class B { public int g() { return new lib.U().u(); } }";

    @Test
    void an_api_change_in_a_sibling_recompiles_only_the_consumer_sources_that_reference_the_changed_class(
            @TempDir Path dir) throws Exception {
        Workspace ws = new Workspace(dir);
        Module lib = ws.module("lib", T1, U);
        Module app = ws.module("app", A, B);
        assertThat(app.build(List.of(lib.jar)).compiled()).containsExactlyInAnyOrder("A.java", "B.java");

        lib.write("lib/T.java", T2);
        lib.buildAndJar();

        Run r = app.build(List.of(lib.jar));
        assertThat(r.outcome()).isEqualTo("compiled");
        assertThat(r.compiled()).containsExactly("A.java");
    }

    @Test
    void a_jar_outside_any_jk_output_tree_still_invalidates_every_class_that_touched_it(@TempDir Path dir)
            throws Exception {
        Workspace ws = new Workspace(dir);
        Module lib = ws.module("lib", T1, U);
        Path foreign = dir.resolve("deps").resolve("lib.jar");
        Files.createDirectories(foreign.getParent());
        Files.copy(lib.jar, foreign);
        Module app = ws.module("app", A, B);
        app.build(List.of(foreign));

        lib.write("lib/T.java", T2);
        lib.buildAndJar();
        Files.copy(lib.jar, foreign, StandardCopyOption.REPLACE_EXISTING);

        assertThat(app.build(List.of(foreign)).compiled()).containsExactlyInAnyOrder("A.java", "B.java");
    }

    @Test
    void an_unchanged_consumer_is_still_an_action_cache_hit(@TempDir Path dir) throws Exception {
        Workspace ws = new Workspace(dir);
        Module lib = ws.module("lib", T1, U);
        Module app = ws.module("app", A, B);
        app.build(List.of(lib.jar));

        Run again = app.build(List.of(lib.jar));
        assertThat(again.outcome()).startsWith("cache-hit");
        assertThat(again.compiled()).isEmpty();
    }

    @Test
    void the_forecast_names_the_sibling_class_whose_api_moved(@TempDir Path dir) throws Exception {
        Workspace ws = new Workspace(dir);
        Module lib = ws.module("lib", T1, U);
        Module app = ws.module("app", A, B);
        app.build(List.of(lib.jar));

        lib.write("lib/T.java", T2);
        lib.buildAndJar();

        JavaCompile.Prediction pred = app.predict(List.of(lib.jar));
        assertThat(pred.outcome()).isEqualTo(JavaCompile.Outcome.INCREMENTAL);
        assertThat(pred.reason()).contains("lib.T");
        assertThat(pred.sources().stream().map(p -> p.getFileName().toString())).containsExactly("A.java");
    }

    // ---- harness ----------------------------------------------------------

    private static final class Workspace {
        final Path root;
        final Path incrementalRoot;
        final Cas cas;
        final ActionCache actionCache;
        final Path workerJar;

        Workspace(Path root) {
            String prop = System.getProperty("jk.java.plugin.jar");
            Assumptions.assumeTrue(
                    prop != null && Files.isRegularFile(Path.of(prop)),
                    "jk.java.plugin.jar must point at the built worker jar");
            this.root = root;
            this.incrementalRoot = root.resolve("cache").resolve("incremental-java");
            this.cas = new Cas(root.resolve("cache").resolve("cas"));
            this.actionCache = new ActionCache(cas, root.resolve("cache").resolve("actions"));
            this.workerJar = Path.of(prop);
        }

        /** A module with two sources under its package, compiled and packaged. */
        Module module(String name, String first, String second) throws IOException {
            Module m = new Module(this, name);
            String pkg = name;
            m.write(pkg + "/" + className(first) + ".java", first);
            m.write(pkg + "/" + className(second) + ".java", second);
            if ("lib".equals(name)) m.buildAndJar();
            return m;
        }

        private static String className(String source) {
            int at = source.indexOf("class ") + "class ".length();
            return source.substring(at, source.indexOf(' ', at));
        }
    }

    private static final class Module {
        final Workspace ws;
        final String taskId;
        final Path src;
        final Path classes;
        final Path jar;
        final Path stateDir;
        final Path gen;

        Module(Workspace ws, String name) throws IOException {
            this.ws = ws;
            this.src = ws.root.resolve(name).resolve("src/main/java");
            Path target = ws.root.resolve("target").resolve(name);
            this.classes = target.resolve("classes").resolve("main");
            this.jar = target.resolve("lib").resolve(name + "-1.0.jar");
            this.taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, classes);
            this.stateDir = ws.incrementalRoot.resolve(taskId);
            this.gen = target.resolve("gen");
            Files.createDirectories(src);
        }

        void write(String rel, String body) throws IOException {
            Path f = src.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body);
        }

        Run build(List<Path> classpath) throws IOException {
            JavaCompile.Result result = JavaCompile.run(
                    taskId,
                    request(classpath),
                    "jk-test",
                    true,
                    ws.cas,
                    ws.actionCache,
                    stateDir,
                    ws.workerJar,
                    gen,
                    WorkerEnv.strict());
            assertThat(result.success()).as(result.diagnostics().toString()).isTrue();
            return new Run(result.outcome(), names(result.compiledSources()));
        }

        void buildAndJar() throws IOException {
            build(List.of());
            Files.createDirectories(jar.getParent());
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                try (var walk = Files.walk(classes)) {
                    for (Path f : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(f)) continue;
                        out.putNextEntry(
                                new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                        out.write(Files.readAllBytes(f));
                        out.closeEntry();
                    }
                }
            }
        }

        JavaCompile.Prediction predict(List<Path> classpath) throws IOException {
            try (JavaCompilerHost.Scope ignored = JavaCompilerHost.open()) {
                return JavaCompile.predict(
                        taskId,
                        request(classpath),
                        "jk-test",
                        ws.actionCache,
                        stateDir,
                        ws.workerJar,
                        gen,
                        WorkerEnv.strict());
            }
        }

        private CompileRequest request(List<Path> classpath) throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java")) sources.add(p);
                }
            }
            return CompileRequest.builder()
                    .sources(sources)
                    .classpath(classpath)
                    .outputDir(classes)
                    .release(21)
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
        }

        private static Set<String> names(List<Path> compiled) {
            Set<String> out = new LinkedHashSet<>();
            for (Path p : compiled) out.add(p.getFileName().toString());
            return out;
        }
    }

    private record Run(String outcome, Set<String> compiled) {}
}
