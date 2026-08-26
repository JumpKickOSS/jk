// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.JobWorkers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end of the launcher → worker subprocess round-trip: launches the real {@code
 * jk-java-compiler} jar (path from the {@code jk.java.plugin.jar} property the Gradle test task
 * sets), compiles a source with a real annotation processor, and asserts the parsed generated →
 * originating provenance.
 */
@Tag("integration")
class ForkedJavacTest {

    @Test
    void launches_the_worker_and_parses_provenance(@TempDir Path dir) throws IOException {
        String workerProp = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                workerProp != null && Files.isRegularFile(Path.of(workerProp)),
                "jk.java.plugin.jar must point at the built worker jar");

        // A standalone annotation processor + annotation, on a processor path.
        Path procDir = dir.resolve("proc");
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
        Files.writeString(
                Files.createDirectories(procDir.resolve("META-INF/services"))
                        .resolve("javax.annotation.processing.Processor"),
                "gen.GenProc\n");

        Path src = dir.resolve("src/app/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app; @gen.Gen public class Widget {}");

        ForkedJavac.Result r = ForkedJavac.compile(new ForkedJavac.Request(
                Path.of(System.getProperty("java.home")),
                Path.of(workerProp),
                List.of(src),
                List.of(procDir), // classpath, so @gen.Gen resolves
                List.of(procDir), // processor path
                dir.resolve("classes"),
                dir.resolve("gen-src"),
                21,
                List.of()));

        assertThat(r.success())
                .as("worker compile succeeded: %s", r.diagnostics())
                .isTrue();
        assertThat(r.generated()).hasSize(1);
        Map.Entry<Path, Set<Path>> prov = r.generated().entrySet().iterator().next();
        assertThat(prov.getKey().toString().replace('\\', '/')).endsWith("app/WidgetGen.java");
        assertThat(prov.getValue())
                .anyMatch(p -> p.toString().replace('\\', '/').endsWith("app/Widget.java"));
        assertThat(dir.resolve("classes/app/WidgetGen.class")).isRegularFile();
    }

    @Test
    void job_scope_reuses_the_worker_for_compile_then_plan(@TempDir Path dir) throws Exception {
        String workerProp = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                workerProp != null && Files.isRegularFile(Path.of(workerProp)),
                "jk.java.plugin.jar must point at the built worker jar");

        Path src = dir.resolve("src/a/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package a; public class A { public int n() { return 1; } }");
        Path worker = Path.of(workerProp);
        Path classes = dir.resolve("classes");
        Path work = dir.resolve("zinc");
        ForkedJavac.Request req = new ForkedJavac.Request(
                Path.of(System.getProperty("java.home")),
                worker,
                List.of(src),
                List.of(),
                List.of(),
                classes,
                dir.resolve("gen"),
                21,
                List.of(),
                work);

        long job = 8801L;
        JobWorkers.open(job);
        try {
            ForkedJavac.Result compiled = ForkedJavac.compile(req);
            assertThat(compiled.success()).as("%s", compiled.diagnostics()).isTrue();
            Files.writeString(src, "package a; public class A { public int n() { return 2; } }");
            ForkedJavac.Plan plan = ForkedJavac.plan(req);
            assertThat(plan.full()).isFalse();
            assertThat(plan.sources()).isNotEmpty();
        } finally {
            JavaCompilerHost.end(job);
            JobWorkers.shutdownForRequest(job, 0L);
            JobWorkers.close();
        }
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
