// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The processor path's classloader is held for the life of the worker instead of being rebuilt for
 * each module, because rebuilding it makes every compile reload the processor's classes from its jar.
 * A build was measured opening one Lombok jar 14,797 times in fifteen seconds, each open followed by
 * a walk of all nine path components to canonicalise it at roughly 92 µs an open on NTFS.
 *
 * <p>What has to survive that reuse is processor freshness: {@code AbstractProcessor.init} is
 * single-shot, so every Zinc cycle needs its own instances even though they now come from
 * already-loaded classes. {@code ZincProcessorLoadingTest.two_loads_share_nothing} pins that for one
 * loader; these pin that the loader is the same one next time, and that a different processor path
 * does not get handed it.
 */
class ZincProcessorLoaderCacheTest {

    @Test
    void the_same_processor_path_is_loaded_once(@TempDir Path tmp) throws Exception {
        Path procDir = writeGeneratingProcessor(tmp.resolve("proc"));

        ZincJavaCompiler.ProcessorLoad first = ZincJavaCompiler.processorsFor(List.of(procDir));
        ZincJavaCompiler.ProcessorLoad second = ZincJavaCompiler.processorsFor(List.of(procDir));

        assertThat(first.any()).isTrue();
        assertThat(second.loader()).isSameAs(first.loader());
    }

    /** A module whose processors differ must not be handed the previous module's loader. */
    @Test
    void a_different_processor_path_gets_its_own_loader(@TempDir Path tmp) throws Exception {
        Path one = writeGeneratingProcessor(tmp.resolve("one"));
        Path two = writeGeneratingProcessor(tmp.resolve("two"));

        ZincJavaCompiler.ProcessorLoad first = ZincJavaCompiler.processorsFor(List.of(one));
        ZincJavaCompiler.ProcessorLoad second = ZincJavaCompiler.processorsFor(List.of(two));

        assertThat(second.loader()).isNotSameAs(first.loader());
    }

    /** No processor path is not a cache key: it stays the shared empty load. */
    @Test
    void no_processor_path_loads_nothing() {
        assertThat(ZincJavaCompiler.processorsFor(List.of()).loader()).isNull();
        assertThat(ZincJavaCompiler.processorsFor(List.of()).any()).isFalse();
    }

    /**
     * The reuse under real conditions: two compiles in this JVM against one processor path, each of
     * which must actually run the processor. If a reused loader broke processor freshness the second
     * compile would fail on {@code Cannot call init more than once}, and if it broke discovery the
     * second compile would silently generate nothing.
     */
    @Test
    void a_second_compile_through_the_reused_loader_still_generates(@TempDir Path tmp) throws Exception {
        Path procDir = writeGeneratingProcessor(tmp.resolve("proc"));

        Path firstOut = compileOnce(tmp, procDir, "first", "a");
        Path secondOut = compileOnce(tmp, procDir, "second", "b");

        assertThat(firstOut.resolve("gen/Generated.class")).exists();
        assertThat(secondOut.resolve("gen/Generated.class")).exists();
    }

    /** One compile of a single source in its own package, returning the class output directory. */
    private static Path compileOnce(Path tmp, Path procDir, String name, String pkg) throws Exception {
        Path src = tmp.resolve(name + "-src/" + pkg + "/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package " + pkg + "; public class A {}");
        Path classes = tmp.resolve(name + "-classes");
        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(src),
                List.of(),
                classes,
                tmp.resolve(name + "-work"),
                tmp.resolve(name + "-gen"),
                25,
                List.of(),
                List.of(procDir)));
        assertThat(r.success())
                .as("%s compile failed: %s", name, r.diagnostics())
                .isTrue();
        return classes;
    }

    /**
     * A processor registered through {@code META-INF/services}, generating {@code gen.Generated} on
     * its first round so that whether it ran is observable in the class output. Each call writes its
     * own directory, so two of them are genuinely different processor paths.
     */
    private static Path writeGeneratingProcessor(Path procDir) throws Exception {
        Files.createDirectories(procDir);
        Path src = procDir.resolve("gen/GenProc.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package gen;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import java.io.Writer;
                import java.util.Set;
                @SupportedAnnotationTypes("*")
                public class GenProc extends AbstractProcessor {
                    private boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                        if (done || r.processingOver()) return false;
                        done = true;
                        try (Writer w = processingEnv.getFiler().createSourceFile("gen.Generated").openWriter()) {
                            w.write("package gen; public class Generated {}");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return false;
                    }
                }
                """);
        FixtureJavac.compile(procDir, src);
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "gen.GenProc\n");
        return procDir;
    }
}
