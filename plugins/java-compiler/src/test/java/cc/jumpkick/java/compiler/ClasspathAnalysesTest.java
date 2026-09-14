// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.FileAnalysisStore;

/**
 * A consumer compile that is handed its producer's Zinc analysis tracks the producer's classes one
 * by one instead of by the jar's stamp. The observable seam is the consumer's own analysis: a class
 * a producer analysis answered for is recorded as an <em>external</em> dependency, everything else
 * as a library file.
 */
class ClasspathAnalysesTest {

    private static final String T1 = "package lib; public class T { public int a() { return 1; } }";
    private static final String T2 =
            "package lib; public class T { public int a() { return 1; } public int b() { return 2; } }";
    private static final String U = "package lib; public class U { public int u() { return 3; } }";
    private static final String A = "package app; public class A { public int f() { return new lib.T().a(); } }";
    private static final String B = "package app; public class B { public int g() { return new lib.U().u(); } }";

    @Test
    void a_producer_api_change_recompiles_only_the_consumer_sources_that_reference_the_changed_class(@TempDir Path dir)
            throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        Consumer informed = new Consumer(dir.resolve("informed"), lib.jar, Map.of(lib.jar, lib.analysis));
        Consumer plain = new Consumer(dir.resolve("plain"), lib.jar, Map.of());
        assertThat(names(informed.compile())).containsExactlyInAnyOrder("A.java", "B.java");
        assertThat(names(plain.compile())).containsExactlyInAnyOrder("A.java", "B.java");
        assertThat(informed.externals()).containsExactlyInAnyOrder("lib.T", "lib.U");
        assertThat(plain.externals()).isEmpty();

        lib.write("lib/T.java", T2);
        lib.compileAndJar();

        // The jar's stamp moved for both consumers; only the informed one knows that U did not.
        assertThat(names(plain.compile())).containsExactlyInAnyOrder("A.java", "B.java");
        assertThat(names(informed.compile())).containsExactly("A.java");
    }

    @Test
    void the_forecast_names_the_producer_class_whose_api_moved(@TempDir Path dir) throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        Consumer app = new Consumer(dir.resolve("app"), lib.jar, Map.of(lib.jar, lib.analysis));
        app.compile();

        lib.write("lib/T.java", T2);
        lib.compileAndJar();

        ZincJavaCompiler.Plan plan = app.plan();
        assertThat(plan.full()).isFalse();
        assertThat(names(plan.sources())).containsExactly("A.java");
        assertThat(plan.reason()).isEqualTo("dependency lib.T changed");
    }

    @Test
    void a_rebuild_with_nothing_changed_compiles_nothing(@TempDir Path dir) throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        Consumer app = new Consumer(dir.resolve("app"), lib.jar, Map.of(lib.jar, lib.analysis));
        app.compile();

        assertThat(names(app.compile())).isEmpty();
        assertThat(app.plan().sources()).isEmpty();
    }

    @Test
    void a_corrupt_producer_analysis_leaves_the_consumer_on_library_stamps_and_stays_in_place(@TempDir Path dir)
            throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        byte[] garbage = "not a zinc analysis".getBytes(StandardCharsets.UTF_8);
        Files.write(lib.analysis, garbage);

        Consumer app = new Consumer(dir.resolve("app"), lib.jar, Map.of(lib.jar, lib.analysis));
        app.compile();
        assertThat(app.externals()).isEmpty();
        assertThat(Files.readAllBytes(lib.analysis)).isEqualTo(garbage);
    }

    @Test
    void a_producer_analysis_that_no_longer_describes_its_classes_is_not_trusted(@TempDir Path dir) throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        // The classes moved on and the analysis did not — the shape a cache restore leaves behind.
        byte[] earlier = Files.readAllBytes(lib.analysis);
        lib.write("lib/T.java", T2);
        lib.compileAndJar();
        Files.write(lib.analysis, earlier);

        Consumer app = new Consumer(dir.resolve("app"), lib.classes, Map.of(lib.classes, lib.analysis));
        app.compile();
        assertThat(app.externals()).isEmpty();
    }

    @Test
    void a_producer_analysis_that_disappears_recompiles_the_classes_it_answered_for(@TempDir Path dir)
            throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        Consumer app = new Consumer(dir.resolve("app"), lib.jar, Map.of(lib.jar, lib.analysis));
        app.compile();
        assertThat(app.externals()).isNotEmpty();

        Files.delete(lib.analysis);

        assertThat(names(app.compile())).containsExactlyInAnyOrder("A.java", "B.java");
        // Recorded as library files now, the way a Maven jar is.
        assertThat(app.externals()).isEmpty();
    }

    @Test
    void a_classes_directory_entry_is_answered_for_the_same_way_as_a_jar(@TempDir Path dir) throws Exception {
        Producer lib = Producer.compiled(dir.resolve("lib"), T1, U);
        Consumer app = new Consumer(dir.resolve("app"), lib.classes, Map.of(lib.classes, lib.analysis));
        app.compile();
        assertThat(app.externals()).containsExactlyInAnyOrder("lib.T", "lib.U");

        lib.write("lib/T.java", T2);
        lib.compileAndJar();
        assertThat(names(app.compile())).containsExactly("A.java");
    }

    private static List<String> names(List<Path> sources) {
        return sources.stream().map(p -> p.getFileName().toString()).toList();
    }

    /** A module whose classes and analysis a consumer is handed; packaged as a jar too. */
    private static final class Producer {
        final Path src;
        final Path classes;
        final Path workdir;
        final Path analysis;
        final Path jar;

        private Producer(Path dir) {
            this.src = dir.resolve("src");
            this.classes = dir.resolve("classes");
            this.workdir = dir.resolve("zinc-work");
            this.analysis = workdir.resolve("zinc");
            this.jar = dir.resolve("lib.jar");
        }

        static Producer compiled(Path dir, String t, String u) throws IOException {
            Producer p = new Producer(dir);
            p.write("lib/T.java", t);
            p.write("lib/U.java", u);
            p.compileAndJar();
            return p;
        }

        void write(String rel, String contents) throws IOException {
            Path f = src.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, contents);
        }

        void compileAndJar() throws IOException {
            ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                    new JavaCompileJob(sources(src), List.of(), classes, workdir, null, 25, List.of(), List.of()));
            assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
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
    }

    /** A module compiled against one producer entry, with whatever analyses it is handed. */
    private static final class Consumer {
        final Path src;
        final Path classes;
        final Path workdir;
        final Path entry;
        final Map<Path, Path> analyses;

        Consumer(Path dir, Path entry, Map<Path, Path> analyses) throws IOException {
            this.src = dir.resolve("src");
            this.classes = dir.resolve("classes");
            this.workdir = dir.resolve("zinc-work");
            this.entry = entry;
            this.analyses = analyses;
            write("app/A.java", A);
            write("app/B.java", B);
        }

        void write(String rel, String contents) throws IOException {
            Path f = src.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, contents);
        }

        /**
         * Each compile runs on a fresh thread, as each build's worker is a fresh JVM: the javac
         * file manager is held per thread and keeps its jars open, so a jar rewritten under a
         * held manager reads through a stale index. A consumer of a jar is never compiled twice by
         * one worker, and the fixture must not pretend otherwise.
         */
        List<Path> compile() throws IOException {
            JavaCompileJob job = job();
            ZincJavaCompiler.Result[] result = new ZincJavaCompiler.Result[1];
            Thread t = Thread.ofPlatform().start(() -> result[0] = ZincJavaCompiler.compileJava(job));
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            ZincJavaCompiler.Result r = result[0];
            assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
            return r.compiledSources();
        }

        ZincJavaCompiler.Plan plan() throws IOException {
            return ZincJavaCompiler.planJava(job());
        }

        private JavaCompileJob job() throws IOException {
            return new JavaCompileJob(sources(src), List.of(entry), classes, workdir, null, 25, List.of(), List.of())
                    .withClasspathAnalyses(analyses);
        }

        /** The producer classes this consumer's analysis tracks by API rather than by library stamp. */
        List<String> externals() {
            Analysis analysis =
                    (Analysis) FileAnalysisStore.binary(workdir.resolve("zinc").toFile())
                            .get()
                            .orElseThrow()
                            .getAnalysis();
            List<String> out = new ArrayList<>();
            var it = analysis.apis().allExternals().iterator();
            while (it.hasNext()) out.add(it.next());
            return out;
        }
    }

    private static List<Path> sources(Path src) throws IOException {
        try (var walk = Files.walk(src)) {
            return walk.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
