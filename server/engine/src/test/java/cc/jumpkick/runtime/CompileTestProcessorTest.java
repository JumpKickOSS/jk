// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.base.CompileSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-test annotation-processor wiring: {@link TestSupport#compileWithCache} hands javac
 * the declared processor path, and when none is declared it hands javac the processors the test
 * classpath registers, exactly as compile-main does. The fixture uses a source-generating
 * processor: a test source annotated with {@code @gen.Gen} references the generated {@code
 * <Type>Gen} type, so if the processor does not run the symbol is missing and the compile fails.
 */
class CompileTestProcessorTest {

    private static final String TEST_SRC =
            "package app; @gen.Gen public class WidgetTest { final Object g = new WidgetTestGen(); }";

    @Test
    void processorRunsOverTestSources_whenProcessorPathProvided(@TempDir Path dir) throws Exception {
        Path procDir = sourceGenProcessor(dir);
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        Path out = dir.resolve("out");

        boolean ok = TestSupport.compileWithCache(
                new NoopContext(),
                "compile-test",
                new PlannerCompile.TestCompile(
                        CompileSupport.collectJavaSources(testSrc),
                        List.of(procDir),
                        List.of(procDir), // processor jar on cp AND processorpath
                        out,
                        21,
                        List.of(),
                        JavacConfig.EMPTY,
                        Path.of(System.getProperty("java.home")),
                        null),
                dir.resolve("gen"),
                new Cas(dir.resolve("cas")),
                dir.resolve("cache"),
                WorkerEnv.strict());

        assertThat(ok).isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile(); // processor ran
        assertThat(out.resolve("app/WidgetTest.class")).isRegularFile();
    }

    @Test
    void classpathProcessorRuns_whenNoProcessorPathIsDeclared(@TempDir Path dir) throws Exception {
        // The processor is registered on the test classpath and nothing is declared: the request
        // builder discovers it, so WidgetTestGen is generated and the test source compiles.
        Path procDir = sourceGenProcessor(dir);
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        Path out = dir.resolve("out");

        boolean ok = compile(dir, testSrc, List.of(procDir), List.of(), out);

        assertThat(ok).isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile();
    }

    @Test
    void declaredProcessorPathShadowsClasspathProcessor(@TempDir Path dir) throws Exception {
        // A declared processor path that names something else is searched alone: the classpath's
        // processor stays silent, WidgetTestGen is never generated and the compile fails.
        Path procDir = sourceGenProcessor(dir);
        Path silent = Files.createDirectories(dir.resolve("silent"));
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        Path out = dir.resolve("out");

        boolean ok = compile(dir, testSrc, List.of(procDir), List.of(silent), out);

        assertThat(ok).isFalse();
        assertThat(out.resolve("app/WidgetTestGen.class")).doesNotExist();
    }

    private static boolean compile(Path dir, Path testSrc, List<Path> classpath, List<Path> processorPath, Path out)
            throws Exception {
        return TestSupport.compileWithCache(
                new NoopContext(),
                "compile-test",
                new PlannerCompile.TestCompile(
                        CompileSupport.collectJavaSources(testSrc),
                        classpath,
                        processorPath,
                        out,
                        21,
                        List.of(),
                        JavacConfig.EMPTY,
                        Path.of(System.getProperty("java.home")),
                        null),
                dir.resolve("gen"),
                new Cas(dir.resolve("cas")),
                dir.resolve("cache"),
                WorkerEnv.strict());
    }

    // ---- fixtures ---------------------------------------------------------

    /** Source-generating processor: emits {@code <Type>Gen} for each {@code @gen.Gen} type. */
    private static Path sourceGenProcessor(Path dir) throws IOException {
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
        return procDir;
    }

    private static void write(Path root, String rel, String body) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
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

    /** Minimal TaskContext — compileWithCache only labels / warns / errors / reweights. */
    private static final class NoopContext implements TaskContext {
        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additionalScope) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
