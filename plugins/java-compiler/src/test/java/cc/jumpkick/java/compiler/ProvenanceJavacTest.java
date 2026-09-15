// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xsbti.PathBasedFile;
import xsbti.VirtualFile;
import xsbti.compile.IncToolOptions;
import xsbti.compile.SingleOutput;

/**
 * Every in-process compile goes through {@link ProvenanceJavac}, whether or not it has a processor
 * path, so that all of them compile through a file manager held across compiles. Three behaviours
 * could be lost by widening it that way, and each is pinned here: a module with no
 * {@code -processorpath} must still be able to run a processor from its own compile classpath, it
 * must not inherit the <em>previous</em> module's processor path from the held manager, and a source
 * this path cannot name as a file must fail loudly instead of being dropped.
 */
class ProvenanceJavacTest {

    /**
     * A compile with no {@code -processorpath} must still be able to run processors it finds on its
     * own compile classpath. Since JDK 23 javac does not look there unless asked with
     * {@code -proc:full}, so that — not the bare default — is the behaviour worth pinning.
     *
     * <p>The regression it guards is silent and severe: {@code setProcessors} with an <em>empty</em>
     * list turns annotation processing off outright, so a module relying on a classpath-registered
     * processor would compile green and lose everything the processor generates. Delete the
     * {@code loader != null} guard in {@link ProvenanceJavac} and this goes red.
     */
    @Test
    void a_module_with_no_processor_path_still_runs_a_processor_from_its_classpath(@TempDir Path dir) throws Exception {
        Path procDir = writeGeneratingProcessor(dir.resolve("proc"));
        Path src = dir.resolve("src/a/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package a; public class A {}");
        Path classes = dir.resolve("classes");

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(src),
                List.of(procDir),
                classes,
                dir.resolve("work"),
                dir.resolve("gen"),
                25,
                List.of("-proc:full"),
                List.of()));

        assertThat(r.success()).as("compile failed: %s", r.diagnostics()).isTrue();
        assertThat(classes.resolve("gen/Generated.class"))
                .as("the classpath-registered processor did not run")
                .exists();
    }

    /**
     * The manager is reused across compiles, and javac sets {@code ANNOTATION_PROCESSOR_PATH} on it
     * from {@code -processorpath}. A module that declares no processor path must not inherit the
     * previous module's: it would silently run that module's processors and generate code into a
     * build that never asked for any.
     */
    @Test
    void a_compile_does_not_inherit_the_previous_compiles_processor_path(@TempDir Path dir) throws Exception {
        Path procDir = writeGeneratingProcessor(dir.resolve("proc"));

        // First: a module whose processor path really does hold the generating processor.
        Path firstClasses = dir.resolve("first-classes");
        ZincJavaCompiler.Result first = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(writeSource(dir.resolve("first-src"), "a")),
                List.of(),
                firstClasses,
                dir.resolve("first-work"),
                dir.resolve("first-gen"),
                25,
                List.of(),
                List.of(procDir)));
        assertThat(first.success())
                .as("first compile failed: %s", first.diagnostics())
                .isTrue();
        assertThat(firstClasses.resolve("gen/Generated.class"))
                .as("fixture is vacuous unless the first compile really runs the processor")
                .exists();

        // Second, on this same thread and so the same manager: no processor path at all.
        Path secondClasses = dir.resolve("second-classes");
        ZincJavaCompiler.Result second = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(writeSource(dir.resolve("second-src"), "b")),
                List.of(),
                secondClasses,
                dir.resolve("second-work"),
                dir.resolve("second-gen"),
                25,
                List.of(),
                List.of()));

        assertThat(second.success())
                .as("second compile failed: %s", second.diagnostics())
                .isTrue();
        assertThat(secondClasses.resolve("gen/Generated.class"))
                .as("the second compile inherited the first compile's processor path")
                .doesNotExist();
    }

    private static Path writeSource(Path srcDir, String pkg) throws Exception {
        Path src = srcDir.resolve(pkg + "/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package " + pkg + "; public class A {}");
        return src;
    }

    /**
     * This path compiles what it can name as a path. A source it cannot name is a defect in the
     * converter Zinc was set up with, and dropping it would ship a jar with a class missing and no
     * diagnostic naming it, so it is refused rather than skipped.
     */
    @Test
    void a_source_that_is_not_a_file_on_disk_is_refused_rather_than_dropped(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        ProvenanceJavac javac = new ProvenanceJavac(null, new ApProvenance(), StandardCharsets.UTF_8, null);
        VirtualFile notOnDisk = new InMemorySource();

        assertThatThrownBy(() -> javac.run(
                        new VirtualFile[] {notOnDisk},
                        new String[] {"-nowarn"},
                        (SingleOutput) classes::toFile,
                        IncToolOptions.of(Optional.empty(), false),
                        new CollectingReporter(),
                        ZincSetup.QuietLogger.INSTANCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mem:///a/A.java");
    }

    /** A {@link VirtualFile} that is deliberately not a {@link PathBasedFile}. */
    private static final class InMemorySource implements VirtualFile {
        private static final byte[] CONTENT = "package a; public class A {}".getBytes(StandardCharsets.UTF_8);

        @Override
        public String id() {
            return "mem:///a/A.java";
        }

        @Override
        public String name() {
            return "A.java";
        }

        @Override
        public String[] names() {
            return new String[] {"a", "A.java"};
        }

        @Override
        public InputStream input() {
            return new ByteArrayInputStream(CONTENT);
        }

        @Override
        public long contentHash() {
            return 1L;
        }

        @Override
        public long sizeBytes() {
            return CONTENT.length;
        }

        @Override
        public String contentHashStr() {
            return "1";
        }
    }

    /**
     * A processor registered through {@code META-INF/services} in {@code procDir}, which generates
     * {@code gen.Generated} on its first round so that whether it ran is observable in the output.
     */
    private static Path writeGeneratingProcessor(Path procDir) throws Exception {
        Files.createDirectories(procDir);
        Path src = procDir.resolve("_src/disc/DiscProc.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package disc;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import java.io.Writer;
                import java.util.Set;
                @SupportedAnnotationTypes("*")
                public class DiscProc extends AbstractProcessor {
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
        Files.writeString(services, "disc.DiscProc\n");
        return procDir;
    }
}
