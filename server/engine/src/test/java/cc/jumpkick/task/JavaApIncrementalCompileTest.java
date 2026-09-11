// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Annotation-processor-aware incremental compilation through {@link JavaCompile} and the Zinc
 * worker: isolating processors stay incremental; aggregating processors force a full recompile.
 */
@Tag("integration")
class JavaApIncrementalCompileTest {

    /**
     * Isolating processor (1 generated file per annotated type → arity 1): the project is detected on
     * build 1, establishes provenance state on the first worker build, then takes the incremental
     * tier on a subsequent body edit — without going stale.
     */
    @Test
    void isolating_processor_detected_then_incrementally_compiles_via_worker(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        Path procDir = isolatingProcessor(dir);
        Project p = new Project(dir, worker, procDir);

        // v1 — first build goes straight through the Zinc worker and establishes the analysis +
        // provenance; the isolating processor generates app.WidgetGen.
        p.write("app/Widget.java", widget("one"));
        JavaCompile.Result b1 = p.build();
        assertThat(b1.success()).isTrue();
        assertThat(p.classFile("app/WidgetGen.class")).isRegularFile();
        assertThat(Files.isRegularFile(p.stateDir.resolve("zinc"))).isTrue();
        assertThat(Files.exists(p.stateDir.resolve("aggregating"))).isFalse();

        p.write("app/Widget.java", widget("two"));
        JavaCompile.Result b2 = p.build();
        assertThat(b2.success()).isTrue();
        assertThat(p.classFile("app/WidgetGen.class")).isRegularFile();
        assertThat(p.classFile("app/Widget.class")).isRegularFile();

        p.write("app/Widget.java", widget("three"));
        JavaCompile.Result b3 = p.build();
        assertThat(b3.success()).isTrue();
        assertThat(p.classFile("app/WidgetGen.class")).isRegularFile();
        assertThat(p.invokeGreet()).isEqualTo("three");
    }

    /**
     * Aggregating processor (one generated file from many originating sources → arity &gt;1): the
     * arity gate classifies it non-isolating, so the project safely stays on full rebuilds instead of
     * risking a stale aggregate from a subset recompile.
     */
    @Test
    void aggregating_processor_is_classified_non_isolating_and_stays_full(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        Path procDir = aggregatingProcessor(dir);
        Project p = new Project(dir, worker, procDir);

        p.write("app/Alpha.java", "package app; @reg.Reg public class Alpha {}");
        p.write("app/Beta.java", "package app; @reg.Reg public class Beta {}");
        p.build();
        p.write("app/Alpha.java", "package app; @reg.Reg public class Alpha { int v; }");
        p.build();
        assertThat(p.classFile("app/Registry.class")).isRegularFile();
        assertThat(Files.isRegularFile(p.stateDir.resolve("aggregating"))).isTrue();

        p.write("app/Beta.java", "package app; @reg.Reg public class Beta { int v; }");
        p.build();
        assertThat(p.classFile("app/Registry.class")).isRegularFile();
    }

    /**
     *: when an isolating processor stops generating a file — its annotation was removed from
     * the origin — the previously generated source and its class must be deleted, not left to ship in
     * the jar. Zinc can't do this because generated files are not in its source set.
     */
    @Test
    void removing_the_annotation_deletes_the_previously_generated_class(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        Path procDir = isolatingProcessor(dir);
        Project p = new Project(dir, worker, procDir);

        p.write("app/Widget.java", widget("one"));
        assertThat(p.build().success()).isTrue();
        assertThat(p.classFile("app/WidgetGen.class")).isRegularFile();

        // Remove @gen.Gen: Widget recompiles, the processor generates nothing, WidgetGen must go.
        p.write("app/Widget.java", "package app; public class Widget { public String greet() { return \"none\"; } }");
        assertThat(p.build().success()).isTrue();
        assertThat(p.classFile("app/Widget.class")).isRegularFile();
        assertThat(p.classFile("app/WidgetGen.class"))
                .as("stale generated class must be pruned once its annotation is removed")
                .doesNotExist();
    }

    /**
     *: a processor that writes a file with no originating elements (arity 0 — a common
     * META-INF/services writer) has unknown provenance and must be treated as aggregating (full
     * rebuild), not silently classified isolating.
     */
    @Test
    void zero_origin_generated_file_forces_full_rebuild(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        Path procDir = zeroOriginProcessor(dir);
        Project p = new Project(dir, worker, procDir);

        p.write("app/Alpha.java", "package app; @reg.Reg public class Alpha {}");
        assertThat(p.build().success()).isTrue();
        assertThat(Files.isRegularFile(p.stateDir.resolve("aggregating")))
                .as("arity-0 provenance must classify the project as aggregating")
                .isTrue();
    }

    // ---- harness ----------------------------------------------------------

    private static Path workerJar() {
        String prop = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                prop != null && Files.isRegularFile(Path.of(prop)),
                "jk.java.plugin.jar must point at the built worker jar");
        return Path.of(prop);
    }

    private static String widget(String tag) {
        return "package app; @gen.Gen public class Widget { public String greet() { return \"" + tag + "\"; } }";
    }

    private final class Project {
        final Path root;
        final Path srcRoot;
        final Path out;
        final Path genSrc;
        final Cas cas;
        final ActionCache actionCache;
        final Path stateDir;
        final Path workerJar;
        final Path procDir;

        Project(Path root, Path workerJar, Path procDir) throws IOException {
            this.root = root;
            this.srcRoot = root.resolve("src/main/java");
            this.out = root.resolve("out");
            this.genSrc = root.resolve("gen-src");
            this.cas = new Cas(root.resolve("cas"));
            this.actionCache = new ActionCache(cas, root.resolve("actions"));
            this.stateDir = root.resolve("state");
            this.workerJar = workerJar;
            this.procDir = procDir;
            Files.createDirectories(srcRoot);
        }

        void write(String rel, String body) throws IOException {
            Path f = srcRoot.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body);
        }

        Path classFile(String rel) {
            return out.resolve(rel);
        }

        JavaCompile.Result build() throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java")) sources.add(p);
                }
            }
            CompileRequest req = CompileRequest.builder()
                    .sources(sources)
                    .classpath(List.of(procDir)) // so @Gen/@Reg resolve
                    .outputDir(out)
                    .release(21)
                    // The processor path makes javac run the AP (modern javac won't
                    // auto-run classpath processors) — the plain-javac build that surfaces
                    // the orphan signal flipping the project into worker mode.
                    .processorPath(List.of(procDir))
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
            JavaCompile.Result r = JavaCompile.run(
                    "compile-main",
                    req,
                    "jk-test",
                    true,
                    cas,
                    actionCache,
                    stateDir,
                    workerJar,
                    genSrc,
                    WorkerEnv.strict());
            assertThat(r.success()).as("compile succeeded: %s", r.diagnostics()).isTrue();
            return r;
        }

        String invokeGreet() throws Exception {
            try (URLClassLoader cl = new URLClassLoader(
                    new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> c = Class.forName("app.Widget", true, cl);
                Object w = c.getDeclaredConstructor().newInstance();
                return (String) c.getMethod("greet").invoke(w);
            }
        }
    }

    // ---- processors compiled onto a processor path ------------------------

    /** Isolating: one {@code <Type>Gen} per annotated type, originating from that type. */
    private static Path isolatingProcessor(Path dir) throws IOException {
        Path procDir = dir.resolve("proc-iso");
        compile(
                procDir,
                Map.of(
                        "gen.Gen", """
                        package gen;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Gen {}
                        """,
                        "gen.GenProc", """
                        package gen;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.JavaFileObject;
                        import java.io.*;
                        import java.util.Set;
                        @SupportedAnnotationTypes("gen.Gen")
                        public class GenProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                for (Element e : r.getElementsAnnotatedWith(Gen.class)) {
                                    if (!(e instanceof TypeElement t)) continue;
                                    String pkg = processingEnv.getElementUtils().getPackageOf(t).getQualifiedName().toString();
                                    String name = (pkg.isEmpty()?"":pkg+".") + t.getSimpleName() + "Gen";
                                    try {
                                        JavaFileObject f = processingEnv.getFiler().createSourceFile(name, t);
                                        try (Writer w = f.openWriter()) {
                                            w.write((pkg.isEmpty()?"":"package "+pkg+";\\n") + "public class " + t.getSimpleName() + "Gen {}\\n");
                                        }
                                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                }
                                return true;
                            }
                        }
                        """));
        registerProcessor(procDir, "gen.GenProc");
        return procDir;
    }

    /** Aggregating: one {@code app.Registry} originating from ALL annotated types. */
    private static Path aggregatingProcessor(Path dir) throws IOException {
        Path procDir = dir.resolve("proc-agg");
        compile(
                procDir,
                Map.of(
                        "reg.Reg", """
                        package reg;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Reg {}
                        """,
                        "reg.RegProc", """
                        package reg;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.JavaFileObject;
                        import java.io.*;
                        import java.util.*;
                        @SupportedAnnotationTypes("reg.Reg")
                        public class RegProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                List<Element> all = new ArrayList<>(r.getElementsAnnotatedWith(Reg.class));
                                if (all.isEmpty()) return true;
                                try {
                                    JavaFileObject f = processingEnv.getFiler().createSourceFile(
                                            "app.Registry", all.toArray(new Element[0]));
                                    try (Writer w = f.openWriter()) {
                                        w.write("package app; public class Registry {}\\n");
                                    }
                                } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                return true;
                            }
                        }
                        """));
        registerProcessor(procDir, "reg.RegProc");
        return procDir;
    }

    /** Writes a resource with NO originating elements (arity 0), like a META-INF/services writer. */
    private static Path zeroOriginProcessor(Path dir) throws IOException {
        Path procDir = dir.resolve("proc-zero");
        compile(
                procDir,
                Map.of(
                        "reg.Reg", """
                        package reg;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Reg {}
                        """,
                        "reg.ZeroProc", """
                        package reg;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.*;
                        import java.io.*;
                        import java.util.Set;
                        @SupportedAnnotationTypes("reg.Reg")
                        public class ZeroProc extends AbstractProcessor {
                            private boolean done;
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                if (done || r.getElementsAnnotatedWith(Reg.class).isEmpty()) return true;
                                done = true;
                                try {
                                    // No originating elements -> arity 0 (unknown provenance).
                                    FileObject f = processingEnv.getFiler().createResource(
                                            StandardLocation.CLASS_OUTPUT, "", "META-INF/services/app.Thing");
                                    try (Writer w = f.openWriter()) { w.write("app.Alpha\\n"); }
                                } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                return true;
                            }
                        }
                        """));
        registerProcessor(procDir, "reg.ZeroProc");
        return procDir;
    }

    private static void registerProcessor(Path procDir, String impl) throws IOException {
        Files.writeString(
                Files.createDirectories(procDir.resolve("META-INF/services"))
                        .resolve("javax.annotation.processing.Processor"),
                impl + "\n");
    }

    private static void compile(Path outDir, Map<String, String> sources) throws IOException {
        Path srcDir = outDir.resolve("_src");
        Files.createDirectories(outDir);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = srcDir.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        List<String> args = new ArrayList<>(List.of("-d", outDir.toString()));
        args.addAll(files);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) throw new IllegalStateException("fixture javac failed, rc=" + rc);
    }
}
