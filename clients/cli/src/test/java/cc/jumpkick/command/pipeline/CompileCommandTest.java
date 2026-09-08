// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.ScaffoldTestSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class CompileCommandTest {

    @Test
    void check_passes_for_clean_source(@TempDir Path tempDir) throws Exception {
        scaffold(tempDir);
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);

        int exit = run(
                "compile",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void check_fails_on_syntax_error(@TempDir Path tempDir) throws Exception {
        scaffold(tempDir);
        Path src = tempDir.resolve("src/main/java/example/Broken.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Broken { void f(   // missing\n");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(
                    "compile",
                    "-C",
                    tempDir.toString(),
                    "--cache-dir",
                    tempDir.resolve("cache").toString());
        } finally {
            System.setOut(originalOut);
        }

        assertThat(exit).isEqualTo(1);
        // The compiler diagnostic is surfaced through jk's error channel (with its
        // file:line + javac message), not dumped as raw compiler text. It prints
        // above the result line (same stream), so the "✗ ... failed" line is last.
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Broken.java")
                .contains("reached end of file");
    }

    @Test
    void check_reports_no_sources_as_clean(@TempDir Path tempDir) throws Exception {
        scaffold(tempDir);
        // No src/ at all — empty project.
        int exit = run(
                "compile",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    // (Removed check_without_lockfile_errors: `jk compile`/`check` no longer
    // requires a pre-existing jk-lock.toml — it runs the shared plan in
    // compile-only mode, which auto-locks like `jk build`/`run`.)

    @Test
    void empty_affected_selection_is_a_no_op_not_the_whole_graph(@TempDir Path tempDir) throws Exception {
        // : the wire treats empty selectedModules as "everything", so an
        // --affected-since that matches nothing must short-circuit client-side.
        Path proj = tempDir.resolve("proj");
        Files.createDirectories(proj);
        scaffold(proj);
        Path src = proj.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {}
                }
                """);
        git(proj, "init", "-q");
        git(proj, "add", ".");
        git(proj, "-c", "user.email=jk@test", "-c", "user.name=jk", "commit", "-qm", "init");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(
                    "compile",
                    "-C",
                    proj.toString(),
                    "--cache-dir",
                    tempDir.resolve("cache").toString(),
                    "--affected-since",
                    "HEAD");
        } finally {
            System.setOut(originalOut);
        }

        assertThat(exit).isEqualTo(0);
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("nothing selected to compile");
        assertThat(out).doesNotContain("Compiled");
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }

    // --- helpers -----------------------------------------------------------

    private static void scaffold(Path dir) throws IOException {
        run("new", dir.toString());
        ScaffoldTestSupport.writeEmptyLock(dir); // jk new no longer locks; check needs a lock
    }
}
