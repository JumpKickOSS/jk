// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk tool run <file>.jar} — the jar target, which shares nothing with the source targets
 * beyond the verb: no compile, no dependency header, no cache directory. Split out of
 * {@code ToolRunCommandTest} when that file passed the 800-line cap (JK-2444); the jar tests and
 * their two jar-building fixtures were the whole of the second subject, so the seam was already
 * there. No assertion changed in the move.
 */
@Tag("integration")
class ToolRunJarTargetTest {

    @Test
    void runs_a_self_contained_jar(@TempDir Path tempDir) throws Exception {
        // Build a tiny runnable jar with Main-Class set.
        Path jar = buildExitJar(tempDir, "RunMe", /* exitCode= */ 0);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void jar_exit_code_is_propagated(@TempDir Path tempDir) throws Exception {
        Path jar = buildExitJar(tempDir, "RunMe", /* exitCode= */ 7);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString());
        assertThat(exit).isEqualTo(7);
    }

    @Test
    void jar_args_are_forwarded(@TempDir Path tempDir) throws Exception {
        Path jar = buildArgCountJar(tempDir, "Echo");

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString(),
                "one",
                "two");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void jar_without_main_class_returns_data_error(@TempDir Path tempDir) throws Exception {
        // Jar with no Main-Class attribute → EX_DATAERR.
        Path jar = tempDir.resolve("no-main.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("placeholder.txt"));
            jos.write("hi".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                jar.toString());
        assertThat(exit).isEqualTo(65);
    }

    @Test
    void jar_not_found_returns_no_input(@TempDir Path tempDir) {
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                tempDir.resolve("missing.jar").toString());
        assertThat(exit).isEqualTo(66);
    }

    /**
     * Build a runnable jar whose {@code Main-Class} calls {@code System.exit(exitCode)}. Used by .jar
     * mode integration tests.
     */
    private static Path buildExitJar(Path tempDir, String className, int exitCode) throws Exception {
        Path src = tempDir.resolve(className + ".java");
        Files.writeString(
                src,
                "public class "
                        + className
                        + " { public static void main(String[] a) { System.exit("
                        + exitCode
                        + "); } }\n");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, src.toString());
        if (rc != 0) throw new IllegalStateException("compile of " + src + " failed");
        Path classFile = src.resolveSibling(className + ".class");

        Path jar = tempDir.resolve(className.toLowerCase() + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, className);
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry(className + ".class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }

    /** Jar whose main exits with {@code args.length} — for testing arg forwarding. */
    private static Path buildArgCountJar(Path tempDir, String className) throws Exception {
        Path src = tempDir.resolve(className + ".java");
        Files.writeString(
                src,
                "public class " + className + " { public static void main(String[] a) { System.exit(a.length); } }\n");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, src.toString());
        if (rc != 0) throw new IllegalStateException("compile of " + src + " failed");
        Path classFile = src.resolveSibling(className + ".class");

        Path jar = tempDir.resolve(className.toLowerCase() + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, className);
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry(className + ".class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }
}
