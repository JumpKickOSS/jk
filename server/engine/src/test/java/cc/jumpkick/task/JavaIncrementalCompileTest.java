// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.compile.JavacFixture;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end incremental Java compilation through {@link JavaCompile} and the Zinc worker. The
 * worker reports which sources each pass compiled.
 */
@Tag("integration")
class JavaIncrementalCompileTest {

    @Test
    void body_only_edit_recompiles_only_that_source(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");

        assertThat(p.build().compiledSources()).containsExactlyInAnyOrder("a/A.java", "a/B.java");

        // Change B's method *body* only — Zinc API hash unchanged.
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hello there\"; } }");
        Run r = p.build();
        assertThat(r.outcome).isEqualTo("compiled");
        assertThat(r.compiledSources()).containsExactly("a/B.java"); // A is NOT recompiled
    }

    @Test
    void abi_change_recompiles_dependents_via_the_dependency_graph(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        // A references B in bytecode (constructs it, calls greet).
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");
        p.build();

        // Public-surface change to B. Zinc may recompile dependents that used B's API.
        p.write(
                "a/B.java",
                "package a; public class B { public String greet() { return \"hi\"; } public void bye() {} }");
        Run r = p.build();
        assertThat(r.compiledSources()).containsExactlyInAnyOrder("a/B.java", "a/A.java");
    }

    @Test
    void changing_an_inlined_constant_recompiles_dependents_conservatively(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        // B.X is a compile-time constant — javac inlines it into A, leaving no
        // bytecode edge, so the constant change must trigger a conservative recompile.
        p.write("a/B.java", "package a; public class B { public static final int X = 1; }");
        p.write("a/A.java", "package a; public class A { public static int v() { return B.X; } }");
        p.build();
        assertThat(invokeStaticInt(p.out, "a.A", "v")).isEqualTo(1);

        p.write("a/B.java", "package a; public class B { public static final int X = 2; }");
        Run r = p.build();
        assertThat(r.compiledSources()).contains("a/A.java"); // A recompiled despite no bytecode edge
        assertThat(invokeStaticInt(p.out, "a.A", "v")).isEqualTo(2); // and picked up the new value
    }

    @Test
    void dependency_abi_change_recompiles_referencing_sources(@TempDir Path dir) throws Exception {
        // Two versions of a dependency at different paths (a dependency bump). v2
        // adds an overload; Zinc invalidates A via the changed classpath stamp.
        JavacFixture.compile(
                dir.resolve("depv1"),
                Map.of("dep.Lib", "package dep; public class Lib { public void f(Object o) {} }"));
        JavacFixture.compile(
                dir.resolve("depv2"),
                Map.of(
                        "dep.Lib",
                        "package dep; public class Lib { public void f(Object o) {} public void f(String s) {} }"));
        Path depV1 = dir.resolve("depv1").resolve("out");
        Path depV2 = dir.resolve("depv2").resolve("out");

        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public void call(dep.Lib lib) { lib.f(\"hi\"); } }");

        p.build(List.of(depV1)); // compile A against dep v1
        Run r = p.build(List.of(depV2)); // dep bumped to v2 (different classpath path)
        assertThat(r.compiledSources()).contains("a/A.java"); // A recompiled because dep/Lib's ABI changed
    }

    @Test
    void no_change_rebuild_is_an_action_cache_hit(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.build();
        Run r = p.build(); // identical inputs
        assertThat(r.outcome).startsWith("cache-hit");
        assertThat(r.compiledSources()).isEmpty(); // nothing forked javac
    }

    @Test
    void removing_a_leaf_source_deletes_its_class_without_recompiling(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.write("a/B.java", "package a; public class B { public int g() { return 2; } }"); // independent
        p.build();

        p.remove("a/B.java");
        Run r = p.build();
        assertThat(r.outcome).isEqualTo("compiled"); // incremental, not a full rebuild
        assertThat(p.classExists("a/B.class")).isFalse(); // the removed class is cleaned up
        assertThat(p.classExists("a/A.class")).isTrue();
    }

    @Test
    void removing_a_referenced_source_recompiles_the_consumer_and_surfaces_the_break(@TempDir Path dir)
            throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public int g() { return 2; } }");
        p.write("a/A.java", "package a; public class A { public int f() { return new B().g(); } }");
        p.build();

        // Remove B but leave A referencing it: A must be recompiled (so the now-dangling
        // reference becomes a real compile error) rather than carried over stale.
        p.remove("a/B.java");
        Run r = p.tryBuild();
        assertThat(r.outcome).isEqualTo("errors");
        assertThat(r.compiledSources()).contains("a/A.java");
    }

    @Test
    void removing_a_constant_holder_recompiles_remaining_sources_conservatively(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        // B is a constant holder: consumers inline B.X with no bytecode edge, so its
        // removal can't be tracked precisely → recompile everything still present.
        p.write("a/B.java", "package a; public class B { public static final int X = 1; }");
        p.write("a/A.java", "package a; public class A { public int f() { return 5; } }"); // no edge to B
        p.write("a/C.java", "package a; public class C { public int g() { return 6; } }"); // no edge to B
        p.build();

        p.remove("a/B.java");
        Run r = p.build();
        assertThat(r.outcome).isEqualTo("compiled");
        // Zinc does not recompile files that never referenced B.
        assertThat(p.classExists("a/B.class")).isFalse();
        assertThat(p.classExists("a/A.class")).isTrue();
        assertThat(p.classExists("a/C.class")).isTrue();
    }

    // ----predict dirty-reason strings for explain ----------------

    @Test
    void ephemeral_full_compile_leaves_no_action_record_or_state(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A {}");

        // jk verify's scratch rebuild: useCache=false (rebuild-pinned) + persist=false.
        Run r = p.build(false, false);

        assertThat(r.outcome).isEqualTo("compiled");
        assertThat(p.classExists("a/A.class")).isTrue();
        // No orphan residue: scratch-salted keys never recur.
        assertThat(p.actionCache.lastFor("compile-main")).isEmpty();
        assertThat(emptyOrMissing(p.stateDir)).as("incremental state untouched").isTrue();
    }

    @Test
    void rebuild_still_stores_the_action_record(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A {}");

        // --redo: bypass reads but persist, so the next explain/build sees CACHE_HIT.
        Run r = p.build(false, true);

        assertThat(r.outcome).isEqualTo("compiled");
        assertThat(p.actionCache.lastFor("compile-main")).isPresent();
    }

    private static boolean emptyOrMissing(Path dir) throws IOException {
        if (!Files.exists(dir)) return true;
        try (var files = Files.list(dir)) {
            return files.findAny().isEmpty();
        }
    }

    @Test
    void predict_first_build_reasons_full_with_no_prior_record(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        JavaCompile.Prediction pred = p.predict();
        assertThat(pred.outcome()).isEqualTo(JavaCompile.Outcome.FULL);
        assertThat(pred.reason()).isEqualTo("no zinc analysis");
        assertThat(pred.sourceCount()).isEqualTo(1);
    }

    @Test
    void predict_cache_hit_has_empty_reason(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.build();
        JavaCompile.Prediction pred = p.predict();
        assertThat(pred.outcome()).isEqualTo(JavaCompile.Outcome.CACHE_HIT);
        assertThat(pred.reason()).isEmpty();
    }

    @Test
    void predict_source_edit_reasons_incremental_with_count(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.write("a/B.java", "package a; public class B { public int g() { return 2; } }");
        p.build();
        p.write("a/A.java", "package a; public class A { public int f() { return 99; } }");
        JavaCompile.Prediction pred = p.predict();
        assertThat(pred.outcome()).isEqualTo(JavaCompile.Outcome.INCREMENTAL);
        assertThat(pred.sourceCount()).isEqualTo(1);
        assertThat(pred.reason()).isEqualTo("1 source changed");
    }

    @Test
    void predict_classpath_change_invalidates_users(@TempDir Path dir) throws Exception {
        JavacFixture.compile(
                dir.resolve("depv1"), Map.of("dep.Lib", "package dep; public class Lib { public void f() {} }"));
        JavacFixture.compile(
                dir.resolve("depv2"),
                Map.of("dep.Lib", "package dep; public class Lib { public void f() {} public void g() {} }"));
        Path depV1 = dir.resolve("depv1").resolve("out");
        Path depV2 = dir.resolve("depv2").resolve("out");

        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public void call(dep.Lib lib) { lib.f(); } }");
        p.build(List.of(depV1));

        JavaCompile.Prediction pred = p.predict(List.of(depV2));
        assertThat(pred.outcome()).isEqualTo(JavaCompile.Outcome.INCREMENTAL);
        assertThat(pred.reason()).contains("classpath");
        assertThat(pred.sources().stream().map(path -> path.getFileName().toString()))
                .contains("A.java");
    }

    @Test
    void predict_release_change_reasons_full(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A { public int f() { return 1; } }");
        p.build();
        JavaCompile.Prediction pred = p.predict(List.of(), 17);
        assertThat(pred.outcome()).isEqualTo(JavaCompile.Outcome.FULL);
        assertThat(pred.reason()).containsAnyOf("release changed", "javac options changed");
    }

    // ---- harness ----------------------------------------------------------

    private static final class Project {
        final Path root;
        final Path srcRoot;
        final Path out;
        final Cas cas;
        final ActionCache actionCache;
        final Path stateDir;
        final Path workerJar;

        Project(Path root) throws IOException {
            String prop = System.getProperty("jk.java.plugin.jar");
            Assumptions.assumeTrue(
                    prop != null && Files.isRegularFile(Path.of(prop)),
                    "jk.java.plugin.jar must point at the built worker jar");
            this.root = root;
            this.srcRoot = root.resolve("src/main/java");
            this.out = root.resolve("out");
            this.cas = new Cas(root.resolve("cas"));
            this.actionCache = new ActionCache(cas, root.resolve("actions"));
            this.stateDir = root.resolve("state");
            this.workerJar = Path.of(prop);
            Files.createDirectories(srcRoot);
        }

        void write(String rel, String body) throws IOException {
            Path f = srcRoot.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body);
        }

        void remove(String rel) throws IOException {
            Files.delete(srcRoot.resolve(rel));
        }

        boolean classExists(String rel) {
            return Files.exists(out.resolve(rel));
        }

        Run build() throws IOException {
            return build(List.of());
        }

        /** Build without asserting success — for tests that expect a compile error. */
        Run tryBuild() throws IOException {
            return build(List.of(), false);
        }

        Run build(List<Path> classpath) throws IOException {
            return build(classpath, true);
        }

        Run build(List<Path> classpath, boolean requireSuccess) throws IOException {
            CompileRequest req = request(classpath, 21);
            JavaCompile.Result result = JavaCompile.run(
                    "compile-main",
                    req,
                    "jk-test",
                    true,
                    cas,
                    actionCache,
                    stateDir,
                    workerJar,
                    root.resolve("gen"),
                    WorkerEnv.strict());
            if (requireSuccess) {
                assertThat(result.success()).as("compile succeeded").isTrue();
            }
            return new Run(result.outcome(), relSources(result.compiledSources()));
        }

        /** Build through the persist seam ({@code useCache}/{@code persist} as given). */
        Run build(boolean useCache, boolean persist) throws IOException {
            CompileRequest req = request(List.of(), 21);
            JavaCompile.Result result = JavaCompile.run(
                    "compile-main",
                    req,
                    "jk-test",
                    useCache,
                    persist,
                    cas,
                    actionCache,
                    stateDir,
                    workerJar,
                    root.resolve("gen"),
                    WorkerEnv.strict());
            assertThat(result.success()).as("compile succeeded").isTrue();
            return new Run(result.outcome(), relSources(result.compiledSources()));
        }

        JavaCompile.Prediction predict() throws IOException {
            return predict(List.of(), 21);
        }

        JavaCompile.Prediction predict(List<Path> classpath) throws IOException {
            return predict(classpath, 21);
        }

        JavaCompile.Prediction predict(List<Path> classpath, int release) throws IOException {
            try (JavaCompilerHost.Scope ignored = JavaCompilerHost.open()) {
                return JavaCompile.predict(
                        "compile-main",
                        request(classpath, release),
                        "jk-test",
                        actionCache,
                        stateDir,
                        workerJar,
                        root.resolve("gen"),
                        WorkerEnv.strict());
            }
        }

        private CompileRequest request(List<Path> classpath, int release) throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java")) sources.add(p);
                }
            }
            return CompileRequest.builder()
                    .sources(sources)
                    .classpath(classpath)
                    .outputDir(out)
                    .release(release)
                    .extraOptions(List.of())
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
        }

        private static Set<String> relSources(List<Path> compiled) {
            Set<String> out = new LinkedHashSet<>();
            for (Path s : compiled) {
                String n = s.toString().replace('\\', '/');
                int idx = n.indexOf("/src/main/java/");
                out.add(
                        idx >= 0
                                ? n.substring(idx + "/src/main/java/".length())
                                : s.getFileName().toString());
            }
            return out;
        }
    }

    private record Run(String outcome, Set<String> compiled) {
        Set<String> compiledSources() {
            return compiled;
        }
    }

    private static int invokeStaticInt(Path out, String fqcn, String method) throws Exception {
        try (URLClassLoader cl = new URLClassLoader(new URL[] {out.toUri().toURL()}, null)) {
            return (int) Class.forName(fqcn, true, cl).getMethod(method).invoke(null);
        }
    }
}
