// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-test annotation-processor wiring: {@link TestSupport#compileWithCache} hands javac
 * the declared processor path, and when none is declared it hands javac the processors the test
 * classpath registers, exactly as compile-main does. The fixture uses a source-generating
 * processor: a test source annotated with {@code @gen.Gen} references the generated {@code
 * <Type>Gen} type, so if the processor does not run the symbol is missing and the compile fails.
 * A second processor stands in for one a plugin contributes, which runs beside the module's own.
 */
class CompileTestProcessorTest {

    private static final String TEST_SRC =
            "package app; @gen.Gen public class WidgetTest { final Object g = new WidgetTestGen(); }";

    /** A test source both processors must run over: it references what each one generates. */
    private static final String BOTH_SRC = "package app; @gen.Gen @gen2.Gen2 public class WidgetTest {"
            + " final Object g = new WidgetTestGen(); final Object g2 = new WidgetTestGen2(); }";

    /** The processor every case compiles against, built once: its fixture javac is not what is under test. */
    private static Path procDir;

    /** A second processor, standing in for one a plugin contributes: {@code @gen2.Gen2} makes {@code <Type>Gen2}. */
    private static Path pluginProcDir;

    /**
     * A processor that fails the compile on every type it sees, standing in for a main-only one such
     * as Helidon's API-stability enforcer, which rejects any public class without its annotation.
     */
    private static Path enforcerDir;

    /** One job scope for the class, so every case compiles on the warm worker a build's compile-test uses. */
    private static JavaCompilerHost.Scope job;

    private @Nullable Long previousScope;

    @BeforeAll
    static void buildProcessor(@TempDir Path shared) throws IOException {
        procDir = sourceGenProcessor(shared.resolve("proc"), "gen", "Gen");
        pluginProcDir = sourceGenProcessor(shared.resolve("plugin-proc"), "gen2", "Gen2");
        enforcerDir = enforcer(shared.resolve("enforcer"));
        job = JavaCompilerHost.open();
        JobWorkers.close();
    }

    @AfterAll
    static void endJob() {
        job.close();
    }

    @BeforeEach
    void bindJob() {
        previousScope = JobWorkers.bind(job.id());
    }

    @AfterEach
    void unbindJob() {
        JobWorkers.restore(previousScope);
    }

    @Test
    void processorRunsOverTestSources_whenProcessorPathProvided(@TempDir Path dir) throws Exception {
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
                                List.of(),
                                out,
                                21,
                                List.of(),
                                JavacConfig.EMPTY,
                                Path.of(System.getProperty("java.home")),
                                null,
                                null),
                        dir.resolve("gen"),
                        new Cas(dir.resolve("cas")),
                        dir.resolve("cache"),
                        WorkerEnv.strict())
                .ok();

        assertThat(ok).isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile(); // processor ran
        assertThat(out.resolve("app/WidgetTest.class")).isRegularFile();
    }

    @Test
    void classpathProcessorRuns_whenNoProcessorPathIsDeclared(@TempDir Path dir) throws Exception {
        // The processor is registered on the test classpath and nothing is declared: the request
        // builder discovers it, so WidgetTestGen is generated and the test source compiles.
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
        Path silent = Files.createDirectories(dir.resolve("silent"));
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        Path out = dir.resolve("out");

        boolean ok = compile(dir, testSrc, List.of(procDir), List.of(silent), out);

        assertThat(ok).isFalse();
        assertThat(out.resolve("app/WidgetTestGen.class")).doesNotExist();
    }

    @Test
    void declaredAndContributedProcessorsBothRun(@TempDir Path dir) throws Exception {
        // The module declares one processor and a plugin contributes another: javac runs both, so
        // a user adding their own processor does not silently drop the plugin's codegen.
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", BOTH_SRC);
        Path out = dir.resolve("out");

        boolean ok =
                compile(dir, testSrc, List.of(procDir, pluginProcDir), List.of(procDir), List.of(pluginProcDir), out);

        assertThat(ok).isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile();
        assertThat(out.resolve("app/WidgetTestGen2.class")).isRegularFile();
    }

    @Test
    void discoveredAndContributedProcessorsBothRun(@TempDir Path dir) throws Exception {
        // Nothing declared, a processor registered on the classpath, and a plugin's contributed: the
        // contribution leaves discovery on, so the classpath's processor still runs beside it.
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", BOTH_SRC);
        Path out = dir.resolve("out");

        boolean ok = compile(dir, testSrc, List.of(procDir, pluginProcDir), List.of(), List.of(pluginProcDir), out);

        assertThat(ok).isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile();
        assertThat(out.resolve("app/WidgetTestGen2.class")).isRegularFile();
    }

    @Test
    void aNonInheritingTestTableKeepsTheMainOnlyProcessorOffTheTests(@TempDir Path dir) throws Exception {
        // [processor-dependencies] holds the enforcer, [test-processor-dependencies] the generator:
        // inheriting, compile-test runs both and the enforcer fails it; inherit = false runs the
        // generator alone, as Maven's testCompile runs its own annotationProcessorPaths.
        Path testSrc = dir.resolve("test").resolve("src");
        write(testSrc, "app/WidgetTest.java", TEST_SRC);
        List<Path> shared = List.of(enforcerDir);
        String tables = """
                name = "m"
                group = "g"
                version = "1"

                [test-processor-dependencies]
                """;
        JkBuild inheriting = JkBuildParser.parse(tables);
        JkBuild own = JkBuildParser.parse(tables + "inherit = false\n");

        List<Path> inherited = ProcessorPaths.forTest(inheriting, shared, shared, List.of(enforcerDir, procDir));
        assertThat(compile(dir.resolve("a"), testSrc, List.of(procDir), inherited, dir.resolve("a/out")))
                .as("the inherited enforcer runs over the tests")
                .isFalse();

        Path out = dir.resolve("b/out");
        List<Path> ownPath = ProcessorPaths.forTest(own, shared, shared, List.of(procDir));
        assertThat(ownPath).containsExactly(procDir);
        assertThat(compile(dir.resolve("b"), testSrc, List.of(procDir), ownPath, out))
                .isTrue();
        assertThat(out.resolve("app/WidgetTestGen.class")).isRegularFile();

        // The key alone: no declared path, so the test classpath's processor is discovered and the
        // enforcer, which only the main table names, still stays off.
        Path discovered = dir.resolve("c/out");
        List<Path> nonePath = ProcessorPaths.forTest(own, shared, shared, List.of());
        assertThat(compile(dir.resolve("c"), testSrc, List.of(procDir), nonePath, discovered))
                .isTrue();
        assertThat(discovered.resolve("app/WidgetTestGen.class")).isRegularFile();
    }

    @Test
    void compileMainHandsJavacTheDeclaredPathThenTheContributedOne(@TempDir Path dir) {
        PlannerCompile.MainCompile in = new PlannerCompile.MainCompile(
                List.of(dir.resolve("src/A.java")),
                List.of(procDir, pluginProcDir),
                List.of(procDir),
                List.of(pluginProcDir),
                BuildLayout.of(dir, JkBuildParser.parse("""
                        name = "m"
                        group = "g"
                        version = "1"
                        """)),
                dir.resolve("out"),
                21,
                List.of(),
                JavacConfig.EMPTY,
                Path.of(System.getProperty("java.home")),
                false,
                false,
                null,
                null);

        assertThat(PlannerCompile.mainCompileRequest(in).processorPath()).containsExactly(procDir, pluginProcDir);
    }

    private static boolean compile(Path dir, Path testSrc, List<Path> classpath, List<Path> processorPath, Path out)
            throws Exception {
        return compile(dir, testSrc, classpath, processorPath, List.of(), out);
    }

    private static boolean compile(
            Path dir, Path testSrc, List<Path> classpath, List<Path> processorPath, List<Path> contributed, Path out)
            throws Exception {
        return TestSupport.compileWithCache(
                        new NoopContext(),
                        "compile-test",
                        new PlannerCompile.TestCompile(
                                CompileSupport.collectJavaSources(testSrc),
                                classpath,
                                processorPath,
                                contributed,
                                out,
                                21,
                                List.of(),
                                JavacConfig.EMPTY,
                                Path.of(System.getProperty("java.home")),
                                null,
                                null),
                        dir.resolve("gen"),
                        new Cas(dir.resolve("cas")),
                        dir.resolve("cache"),
                        WorkerEnv.strict())
                .ok();
    }

    // ---- fixtures ---------------------------------------------------------

    /** Source-generating processor: emits {@code <Type><ann>} for each {@code @<pkg>.<ann>} type. */
    private static Path sourceGenProcessor(Path proc, String pkg, String ann) throws IOException {
        compile(
                proc,
                Map.of(
                        pkg + "." + ann,
                        """
                        package $PKG;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface $ANN {}
                        """.replace("$PKG", pkg).replace("$ANN", ann),
                        pkg + "." + ann + "Proc",
                        """
                        package $PKG;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.JavaFileObject;
                        import java.io.*;
                        import java.util.Set;
                        @SupportedAnnotationTypes("$PKG.$ANN")
                        public class $ANNProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                for (Element e : r.getElementsAnnotatedWith($ANN.class)) {
                                    if (!(e instanceof TypeElement t)) continue;
                                    String p = processingEnv.getElementUtils().getPackageOf(t).getQualifiedName().toString();
                                    String name = (p.isEmpty()?"":p+".") + t.getSimpleName() + "$ANN";
                                    try {
                                        JavaFileObject f = processingEnv.getFiler().createSourceFile(name, t);
                                        try (Writer w = f.openWriter()) {
                                            w.write((p.isEmpty()?"":"package "+p+";\\n") + "public class " + t.getSimpleName() + "$ANN {}\\n");
                                        }
                                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                }
                                return true;
                            }
                        }
                        """.replace("$PKG", pkg).replace("$ANN", ann)));
        Files.writeString(
                Files.createDirectories(proc.resolve("META-INF/services"))
                        .resolve("javax.annotation.processing.Processor"),
                pkg + "." + ann + "Proc\n");
        return proc;
    }

    /** A processor that reports an error on every root type, as a stability enforcer does on an unannotated API. */
    private static Path enforcer(Path proc) throws IOException {
        compile(proc, Map.of("enforce.EnforcerProc", """
                        package enforce;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.Diagnostic;
                        import java.util.Set;
                        @SupportedAnnotationTypes("*")
                        public class EnforcerProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                for (Element e : r.getRootElements()) {
                                    processingEnv.getMessager().printMessage(
                                            Diagnostic.Kind.ERROR, "Public API is missing stability annotation", e);
                                }
                                return false;
                            }
                        }
                        """));
        Files.writeString(
                Files.createDirectories(proc.resolve("META-INF/services"))
                        .resolve("javax.annotation.processing.Processor"),
                "enforce.EnforcerProc\n");
        return proc;
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
