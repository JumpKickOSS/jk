// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zinc's Java analysis loads a compiled class's library supertypes with the worker's own JDK and
 * reflects over their members; a supertype whose signature names an API that JDK no longer has —
 * PicketBox's login module returns {@code java.security.acl.Group[]}, gone since 14 — cannot be
 * reflected over. javac itself compiled the module at {@code --release 11} without complaint, so
 * the worker finishes the compile without incremental analysis, says so once as a warning naming
 * the type, and keeps that decision for the workdir so later compiles do not try the analysis again.
 */
class ZincJavaCompilerAnalysisOffTest {

    @Test
    void a_supertype_the_worker_jdk_cannot_load_compiles_without_incremental_analysis(@TempDir Path dir)
            throws Exception {
        Path libSrc = dir.resolve("libsrc/lib/Base.java");
        Files.createDirectories(libSrc.getParent());
        Files.writeString(libSrc, """
                package lib;
                public abstract class Base {
                    public abstract java.security.acl.Group[] roles();
                }
                """);
        Path lib = dir.resolve("lib");
        javacAtRelease11(lib, libSrc);

        Path src = dir.resolve("src/a/Impl.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package a;
                public class Impl extends lib.Base {
                    @Override public java.security.acl.Group[] roles() { return null; }
                }
                """);
        Path workdir = dir.resolve("zinc-work");
        Path classes = dir.resolve("classes");
        JavaCompileJob job =
                new JavaCompileJob(List.of(src), List.of(lib), classes, workdir, null, 11, List.of(), List.of());

        ZincJavaCompiler.Result first = ZincJavaCompiler.compileJava(job);

        assertThat(first.success()).as("diagnostics: %s", first.diagnostics()).isTrue();
        assertThat(classes.resolve("a/Impl.class")).isRegularFile();
        assertAnalysisOffWarning(first);
        assertThat(workdir.resolve("zinc")).as("no analysis is kept").doesNotExist();

        ZincJavaCompiler.Result second = ZincJavaCompiler.compileJava(job);

        assertThat(second.success()).as("diagnostics: %s", second.diagnostics()).isTrue();
        assertAnalysisOffWarning(second);
        assertThat(classes.resolve("a/Impl.class")).isRegularFile();
    }

    /**
     * One analysis-off warning naming the type, beside javac's own removal warning for the source's
     * use of the API — said once, not once per javac run — and no error.
     */
    private static void assertAnalysisOffWarning(ZincJavaCompiler.Result result) {
        assertThat(result.diagnostics()).allSatisfy(d -> assertThat(d.kind()).isEqualTo("WARNING"));
        assertThat(result.diagnostics())
                .filteredOn(d -> d.message().startsWith("compiled without incremental analysis"))
                .singleElement()
                .extracting(ZincJavaCompiler.Diag::message)
                .asString()
                .contains("java.security.acl.Group");
        assertThat(result.diagnostics())
                .filteredOn(d -> d.message().contains("deprecated and marked for removal"))
                .hasSize(1);
    }

    /** javac at {@code --release 11}, where {@code java.security.acl} still exists in ct.sym. */
    private static void javacAtRelease11(Path dest, Path source) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Files.createDirectories(dest);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(
                null,
                null,
                new PrintStream(err, true, StandardCharsets.UTF_8),
                "--release",
                "11",
                "-proc:none",
                "-d",
                dest.toString(),
                source.toString());
        if (rc != 0) throw new IllegalStateException("fixture javac failed:\n" + err.toString(StandardCharsets.UTF_8));
    }
}
