// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavacRunnerTest {

    @Test
    void compiles_clean_source(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Hello.java");
        Files.writeString(source, """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(source))
                        .outputDir(tempDir.resolve("out"))
                        .build());

        assertThat(result.success()).isTrue();
        assertThat(result.hasErrors()).isFalse();
        assertThat(tempDir.resolve("out/Hello.class")).exists();
    }

    /** An export of a system module is refused beside {@code --release}, so the level rides as source/target. */
    @Test
    void an_export_of_a_system_module_compiles_at_the_level_with_source_and_target(@TempDir Path tempDir)
            throws IOException {
        Path source = tempDir.resolve("UsesInternal.java");
        Files.writeString(source, """
                public class UsesInternal {
                    public static Class<?> tool() { return jdk.javadoc.internal.tool.Main.class; }
                }
                """);

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(source))
                        .outputDir(tempDir.resolve("out"))
                        .release(17)
                        .extraOptions(List.of(
                                "--add-modules",
                                "jdk.javadoc",
                                "--add-exports",
                                "jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED"))
                        .build());

        assertThat(result.diagnostics()).noneMatch(d -> d.severity() == CompileResult.Severity.ERROR);
        assertThat(result.success()).isTrue();
        assertThat(tempDir.resolve("out/UsesInternal.class")).exists();
    }

    @Test
    void reports_syntax_error(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Broken.java");
        Files.writeString(source, "public class Broken { void f(  // missing brace\n");

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(source))
                        .outputDir(tempDir.resolve("out"))
                        .build());

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.diagnostics())
                .anySatisfy(d -> assertThat(d.severity()).isEqualTo(CompileResult.Severity.ERROR));
    }

    @Test
    void jvm_unsafe_host_banners_are_noise_not_javac_warnings() {
        // JEP 498 four-line form (Lombok, KSP, …) must not pollute the warning channel.
        assertThat(JavacRunner.isJvmHostNoise(
                        "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called"))
                .isTrue();
        assertThat(JavacRunner.isJvmHostNoise(
                        "WARNING: sun.misc.Unsafe::objectFieldOffset has been called by lombok.permit.Permit"))
                .isTrue();
        assertThat(JavacRunner.isJvmHostNoise(
                        "WARNING: Please consider reporting this to the maintainers of class lombok.permit.Permit"))
                .isTrue();
        assertThat(JavacRunner.isJvmHostNoise(
                        "WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release"))
                .isTrue();
        // Real javac header-less warnings stay.
        assertThat(JavacRunner.isJvmHostNoise(
                        "warning: [options] system modules path not set in conjunction with -source 11"))
                .isFalse();
        assertThat(JavacRunner.isJvmHostNoise("error: error reading corrupt.jar; zip END header not found"))
                .isFalse();
        // : lowercase warning: = javac / annotation-processor Messager channel — a
        // processor warning that mentions the banner phrases must reach diagnostics.
        assertThat(JavacRunner.isJvmHostNoise(
                        "warning: generated code calls a terminally deprecated method in sun.misc.Unsafe"))
                .isFalse();
        assertThat(JavacRunner.isJvmHostNoise(
                        "warning: please consider reporting this to the maintainers of class Foo"))
                .isFalse();
    }

    @Test
    void captures_deprecation_warning_with_severity(@TempDir Path tempDir) throws IOException {
        // Uses a (non-removal) deprecated API; with -Xlint:deprecation javac emits
        // a WARNING. The build still succeeds — warnings are surfaced, not fatal.
        Path source = tempDir.resolve("UsesDeprecated.java");
        Files.writeString(source, """
                public class UsesDeprecated {
                    @SuppressWarnings("unused")
                    static int year() { return new java.util.Date().getYear(); }
                }
                """);

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(source))
                        .outputDir(tempDir.resolve("out"))
                        .extraOptions(List.of("-Xlint:deprecation"))
                        .build());

        assertThat(result.success()).isTrue(); // warnings don't fail the build
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.severity()).isEqualTo(CompileResult.Severity.WARNING);
            assertThat(d.describe()).doesNotStartWith("warning:"); // no severity prefix
        });
    }

    @Test
    void surfaces_headerless_fatal_error_as_diagnostic(@TempDir Path tempDir) throws IOException {
        // A corrupt classpath jar kills javac with a header-less line ("error: error
        // reading …; zip END header not found") and a non-zero exit — no file:line:
        // diagnostic at all. That text must still surface as an ERROR diagnostic;
        // a failed compile must never be silent.
        Path corrupt = tempDir.resolve("corrupt.jar");
        Files.writeString(corrupt, "not-a-zip");
        Path source = tempDir.resolve("Hello.java");
        Files.writeString(source, "public class Hello {}\n");

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(source))
                        .classpath(List.of(corrupt))
                        .outputDir(tempDir.resolve("out"))
                        .build());

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.severity()).isEqualTo(CompileResult.Severity.ERROR);
            assertThat(d.describe()).contains("error reading");
        });
    }

    @Test
    void check_mode_does_not_write_class_files(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Hello.java");
        Files.writeString(source, """
                public class Hello { public static void main(String[] a) {} }
                """);
        Path out = tempDir.resolve("out");

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(source))
                        // outputDir not set → check mode
                        .build());

        assertThat(result.success()).isTrue();
        assertThat(out).doesNotExist();
    }

    @Test
    void picks_up_classpath_dependency(@TempDir Path tempDir) throws IOException {
        // Compile a tiny "library" first, then a "consumer" that uses it via classpath.
        Path libDir = tempDir.resolve("lib-out");
        Path libSrc = tempDir.resolve("Lib.java");
        Files.writeString(libSrc, """
                package lib;
                public final class Lib {
                    public static String greet() { return "hi"; }
                }
                """);
        // Move into a package directory javac expects.
        Path libPkg = tempDir.resolve("lib");
        Files.createDirectories(libPkg);
        Files.move(libSrc, libPkg.resolve("Lib.java"));

        CompileResult libResult = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(libPkg.resolve("Lib.java")))
                        .outputDir(libDir)
                        .build());
        assertThat(libResult.success()).isTrue();

        Path consumer = tempDir.resolve("Use.java");
        Files.writeString(consumer, """
                public class Use {
                    public static void main(String[] args) {
                        System.out.println(lib.Lib.greet());
                    }
                }
                """);

        CompileResult result = new JavacRunner()
                .compile(CompileRequest.builder()
                        .sources(List.of(consumer))
                        .classpath(List.of(libDir))
                        .outputDir(tempDir.resolve("consumer-out"))
                        .build());

        assertThat(result.success()).isTrue();
    }

    @Test
    void diagnostic_renders_with_location() {
        Path file = Path.of("src/Foo.java");
        CompileResult.Diagnostic d =
                new CompileResult.Diagnostic(CompileResult.Severity.ERROR, file, 12, 7, "missing semicolon");
        // Path.toString() uses the platform separator (backslash on Windows).
        assertThat(d.render()).isEqualTo("error: " + file + ":12:7: missing semicolon");
    }
}
