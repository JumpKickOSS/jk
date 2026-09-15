// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the plugin's protocol end-to-end (in-process via {@link JavaIncrementalCompiler#run}): a real
 * annotation processor is ServiceLoader-discovered from a processor path, runs, and its
 * generated-file provenance is reported as JSONL.
 */
class JavaIncrementalCompilerTest {

    @Test
    void compiles_with_processor_and_reports_provenance(@TempDir Path dir) throws Exception {
        // A standalone annotation processor + annotation, compiled into procDir and
        // registered via META-INF/services so the plugin discovers it.
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
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "gen.GenProc\n");

        Path src = dir.resolve("src/app/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app; @gen.Gen public class Widget {}");

        Path classOut = dir.resolve("classes");
        Path genOut = dir.resolve("gen-src");
        Path spec = dir.resolve("spec.txt");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                        .configInt("release", 21)
                        .layout(Map.of("classesDir", classOut, "sourceOutput", genOut))
                        .source(src.toAbsolutePath())
                        .cp(procDir, PluginProtocol.ROLE_COMPILE) // resolves @gen.Gen
                        .cp(procDir, PluginProtocol.ROLE_PROCESSOR)
                        .lines());

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = JavaIncrementalCompiler.compileSpec(
                spec, new ProtocolWriter(new PrintStream(buf, true, StandardCharsets.UTF_8), "##JKJC:"));
        String out = buf.toString(StandardCharsets.UTF_8);

        assertThat(code).isZero();
        assertThat(out).contains("\"t\":\"result\",\"status\":\"OK\"");
        assertThat(out).contains("\"t\":\"provenance\"");
        assertThat(out).contains("WidgetGen");
        assertThat(out).contains("Widget.java");
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
    }

    @Test
    void a_second_incremental_round_gets_fresh_processor_instances(@TempDir Path dir) throws Exception {
        // AbstractProcessor.init is single-shot, and Zinc calls the Java compiler once per
        // incremental round. Reusing one processor set across rounds fails the whole compile with
        // "Cannot call init more than once." — only reachable when round 1 invalidates more work,
        // which is why it survived a green first build.
        Path procDir = dir.resolve("proc");
        writeGenProcessor(procDir);

        Path base = dir.resolve("src/app/Base.java");
        Path user = dir.resolve("src/app/User.java");
        Files.createDirectories(base.getParent());
        Files.writeString(base, "package app; public class Base { public int n() { return 1; } }");
        Files.writeString(user, "package app; @gen.Gen public class User { long m() { return new Base().n(); } }");

        Path classOut = dir.resolve("classes");
        Path genOut = dir.resolve("gen-src");
        Path workdir = dir.resolve("zinc-work");

        assertThat(compileBoth(dir, procDir, classOut, genOut, workdir, base, user))
                .as("first compile")
                .isZero();

        // Change Base's return type so Zinc recompiles Base, then discovers User depends on the
        // changed signature and must follow — a second javac round inside one invocation. User
        // widens to long, so it stays well-typed and only the round count differs.
        Files.writeString(base, "package app; public class Base { public long n() { return 1L; } }");

        String out = captureCompile(dir, procDir, classOut, genOut, workdir, base, user);
        assertThat(out).doesNotContain("Cannot call init more than once");
        assertThat(out).contains("\"t\":\"result\",\"status\":\"OK\"");
    }

    @Test
    void zinc_compile_emits_compiled_source_list(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/a/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package a; public class Hello { public int n() { return 1; } }");
        Path classOut = dir.resolve("classes");
        Path workdir = dir.resolve("zinc-work");
        Path spec = dir.resolve("spec.txt");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                        .configInt("release", 25)
                        .layout(Map.of("classesDir", classOut, "sourceOutput", dir.resolve("gen"), "workdir", workdir))
                        .source(src.toAbsolutePath())
                        .lines());

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = JavaIncrementalCompiler.compileSpec(
                spec, new ProtocolWriter(new PrintStream(buf, true, StandardCharsets.UTF_8), "##JKJC:"));
        String out = buf.toString(StandardCharsets.UTF_8);

        assertThat(code).isZero();
        assertThat(out).contains("\"t\":\"result\"");
        assertThat(out).contains("\"status\":\"OK\"");
        assertThat(out).contains("\"compiled\":[");
        assertThat(out).contains("Hello.java");
        assertThat(classOut.resolve("a/Hello.class")).isRegularFile();
        assertThat(workdir.resolve("zinc")).isRegularFile();
    }

    @Test
    void a_generated_file_whose_annotation_was_removed_is_pruned(@TempDir Path dir) throws Exception {
        // GeneratedProvenance is the only thing that can do this. Generated files are not in Zinc's
        // source set, so WidgetGen.class is an unattributed product Zinc never prunes: without the
        // provenance map from the previous run, dropping @gen.Gen leaves a stale class in the jar.
        Path procDir = dir.resolve("proc");
        writeGenProcessor(procDir);

        Path src = dir.resolve("src/app/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app; @gen.Gen public class Widget {}");

        Path classOut = dir.resolve("classes");
        Path genOut = dir.resolve("gen-src");
        Path workdir = dir.resolve("zinc-work");

        assertThat(compileBoth(dir, procDir, classOut, genOut, workdir, src))
                .as("first compile")
                .isZero();
        assertThat(genOut.resolve("app/WidgetGen.java")).isRegularFile();
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
        assertThat(workdir.resolve("provenance.tsv")).isRegularFile();

        // Same class, no annotation: the processor generates nothing this round.
        Files.writeString(src, "package app; public class Widget {}");
        assertThat(compileBoth(dir, procDir, classOut, genOut, workdir, src))
                .as("second compile")
                .isZero();

        assertThat(genOut.resolve("app/WidgetGen.java")).doesNotExist();
        assertThat(classOut.resolve("app/WidgetGen.class")).doesNotExist();
        assertThat(classOut.resolve("app/Widget.class")).isRegularFile();
    }

    @Test
    void a_generated_file_is_pruned_when_the_output_root_is_reached_through_a_symlink(@TempDir Path dir)
            throws Exception {
        // The same prune, with one link between the build's idea of the output root and the real
        // one. javac real-paths the URIs it hands back from Filer, so provenance holds
        // <real>/gen-src/app/WidgetGen.java while the spec's sourceOutput says <link>/gen-src; a
        // containment test that compares those textually is false and the class file outlives the
        // source it was generated from. macOS gets here unaided — $TMPDIR is under the
        // /var -> /private/var link, so the test above is already this test there — which is
        // exactly why the link is explicit here: on every other platform that one is a green test
        // of a path this defect never takes.
        Path real = Files.createDirectories(dir.resolve("real"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), real);

        Path procDir = dir.resolve("proc");
        writeGenProcessor(procDir);

        Path src = link.resolve("src/app/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app; @gen.Gen public class Widget {}");

        Path classOut = link.resolve("classes");
        Path genOut = link.resolve("gen-src");
        Path workdir = link.resolve("zinc-work");

        assertThat(compileBoth(dir, procDir, classOut, genOut, workdir, src))
                .as("first compile")
                .isZero();
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();

        Files.writeString(src, "package app; public class Widget {}");
        assertThat(compileBoth(dir, procDir, classOut, genOut, workdir, src))
                .as("second compile")
                .isZero();

        // Through the link and through the real path: one file, and it is gone either way.
        assertThat(classOut.resolve("app/WidgetGen.class")).doesNotExist();
        assertThat(real.resolve("classes/app/WidgetGen.class")).doesNotExist();
        assertThat(genOut.resolve("app/WidgetGen.java")).doesNotExist();
        assertThat(classOut.resolve("app/Widget.class")).isRegularFile();
    }

    private static void compile(Path outDir, Map<String, String> sources) throws IOException {
        Path srcDir = outDir.resolve("_src");
        Files.createDirectories(outDir);
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = srcDir.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f);
        }
        FixtureJavac.compile(outDir, files.toArray(Path[]::new));
    }

    /** The {@code gen.Gen} annotation + {@code gen.GenProc} processor, ServiceLoader-registered in {@code procDir}. */
    private static void writeGenProcessor(Path procDir) throws Exception {
        compile(procDir, Map.of("gen.Gen", """
                        package gen;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Gen {}
                        """, "gen.GenProc", """
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
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "gen.GenProc\n");
    }

    /** Exit code of one {@code compileSpec} over {@code sources}, sharing {@code workdir} so Zinc stays incremental. */
    private static int compileBoth(Path dir, Path procDir, Path classOut, Path genOut, Path workdir, Path... sources)
            throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        return runSpec(dir, procDir, classOut, genOut, workdir, buf, sources);
    }

    /** As {@link #compileBoth} but returns the JSONL the run emitted. */
    private static String captureCompile(
            Path dir, Path procDir, Path classOut, Path genOut, Path workdir, Path... sources) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        runSpec(dir, procDir, classOut, genOut, workdir, buf, sources);
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static int runSpec(
            Path dir,
            Path procDir,
            Path classOut,
            Path genOut,
            Path workdir,
            ByteArrayOutputStream buf,
            Path... sources)
            throws Exception {
        var spec = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                .configInt("release", 21)
                .layout(Map.of("classesDir", classOut, "sourceOutput", genOut, "workdir", workdir));
        for (Path s : sources) spec = spec.source(s.toAbsolutePath());
        spec = spec.cp(procDir, PluginProtocol.ROLE_COMPILE).cp(procDir, PluginProtocol.ROLE_PROCESSOR);
        Path specFile = Files.createTempFile(dir, "spec", ".txt");
        Files.write(specFile, spec.lines());
        return JavaIncrementalCompiler.compileSpec(
                specFile, new ProtocolWriter(new PrintStream(buf, true, StandardCharsets.UTF_8), "##JKJC:"));
    }
}
