// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.mavenPath;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.host.Os;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk tool run} and {@code jk tool install} of {@code .kts} and {@code .groovy} scripts: each
 * compiles once into {@code tool-scripts/} and later runs use those classes.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class ToolRunScriptLanguagesTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    // --- .kts --------------------------------------------------------------

    @Test
    void a_kts_script_gets_its_args_and_a_second_run_does_not_compile(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("count.kts");
        Files.writeString(script, """
                val total = args.size + listOf(1, 2).sum()
                kotlin.system.exitProcess(total)
                """);

        assertThat(toolRun(dir, script, "a", "b")).isEqualTo(5);
        Path marker = onlyMarker(dir);
        long compiled = backdate(marker);

        assertThat(toolRun(dir, script, "a")).isEqualTo(4);
        assertThat(Files.getLastModifiedTime(marker).toMillis())
                .as("an unchanged script runs from its cached classes")
                .isEqualTo(compiled);
    }

    @Test
    void an_edited_kts_script_compiles_anew(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("edit.kts");
        Files.writeString(script, "kotlin.system.exitProcess(3)\n");
        assertThat(toolRun(dir, script)).isEqualTo(3);

        Files.writeString(script, "kotlin.system.exitProcess(4)\n");
        assertThat(toolRun(dir, script)).isEqualTo(4);
        assertThat(markers(dir)).hasSize(2);
    }

    /** kotlinc names a script's class after its file; a name that is no identifier still runs. */
    @Test
    void a_kts_script_whose_name_is_no_identifier_runs(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("my-tool.v2.kts");
        Files.writeString(script, "kotlin.system.exitProcess(args.size)\n");
        assertThat(toolRun(dir, script, "x", "y")).isEqualTo(2);
    }

    @Test
    void a_kts_compile_error_fails_the_run(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("broken.kts");
        Files.writeString(script, "val x: Int = \"not an int\"\n");
        assertThat(toolRun(dir, script)).isEqualTo(1);
        assertThat(markers(dir)).isEmpty();
    }

    // --- .groovy -----------------------------------------------------------

    @Test
    void a_groovy_script_gets_its_args_and_a_second_run_does_not_compile(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("count.groovy");
        Files.writeString(script, """
                def total = args.length + [1, 2].sum()
                System.exit(total)
                """);

        assertThat(toolRun(dir, script, "a", "b")).isEqualTo(5);
        Path marker = onlyMarker(dir);
        assertThat(marker.resolveSibling("../script.jar").normalize()).isNotEmptyFile();
        long compiled = backdate(marker);

        assertThat(toolRun(dir, script, "a")).isEqualTo(4);
        assertThat(Files.getLastModifiedTime(marker).toMillis())
                .as("an unchanged script runs from its cached classes")
                .isEqualTo(compiled);
    }

    @Test
    void a_groovy_scripts_deps_line_is_on_its_compile_and_run_classpath(@TempDir Path dir) throws Exception {
        // The pinned Groovy is fetched from the default repositories by a first run; the second
        // pins the mock repository, which serves only the dependency.
        Path warm = dir.resolve("warm.groovy");
        Files.writeString(warm, "System.exit(0)\n");
        assertThat(toolRun(dir, warm)).isZero();

        maven.servePom("com.example", "groovy-greeter", "1.0.0");
        maven.served()
                .put(mavenPath("com.example", "groovy-greeter", "1.0.0", "jar"), Files.readAllBytes(greeterJar(dir)));
        Path script = dir.resolve("greet.groovy");
        Files.writeString(script, """
                //DEPS com.example:groovy-greeter:1.0.0
                import com.example.GroovyGreeter
                System.exit(GroovyGreeter.exitCode())
                """);

        List<String> argv = new ArrayList<>(base(dir, "run"));
        argv.addAll(List.of("--repo-url", maven.base().toString(), script.toString()));
        assertThat(run(argv.toArray(String[]::new))).isEqualTo(17);
    }

    @Test
    void a_groovy_line_runs_the_script_on_that_groovy(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("which.groovy");
        Files.writeString(script, """
                //GROOVY 4.0.27
                System.exit(GroovySystem.version == '4.0.27' ? 4 : 1)
                """);
        assertThat(toolRun(dir, script)).isEqualTo(4);
    }

    @Test
    void a_groovy_runtime_failure_and_a_compile_error_fail_the_run(@TempDir Path dir) throws Exception {
        Path throwing = dir.resolve("throws.groovy");
        Files.writeString(throwing, "throw new IllegalStateException('boom')\n");
        assertThat(toolRun(dir, throwing)).isEqualTo(1);

        Path broken = dir.resolve("broken.groovy");
        Files.writeString(broken, "def x = )\n");
        assertThat(toolRun(dir, broken)).isEqualTo(1);
        assertThat(markers(dir))
                .as("only the script that compiled has a marker")
                .hasSize(1);
    }

    // --- tool install ------------------------------------------------------

    @Test
    void installed_kts_and_groovy_scripts_run_from_their_snapshots(@TempDir Path dir) throws Exception {
        Path kts = dir.resolve("kcount.kts");
        Files.writeString(kts, "kotlin.system.exitProcess(args.size + 10)\n");
        Path groovy = dir.resolve("gcount.groovy");
        Files.writeString(groovy, "System.exit(args.length + 20)\n");
        Path bin = dir.resolve("bin");

        for (Path script : List.of(kts, groovy)) {
            List<String> argv = new ArrayList<>(base(dir, "install"));
            argv.addAll(List.of("--bin-dir", bin.toString(), script.toString()));
            int exit = run(argv.toArray(String[]::new));
            assertThat(exit).as(script.getFileName().toString()).isZero();
            Files.delete(script);
        }

        assertThat(launch(bin, "kcount", "a")).isEqualTo(11);
        assertThat(launch(bin, "gcount", "a", "b")).isEqualTo(22);
    }

    // --- helpers -----------------------------------------------------------

    /** {@code jk tool <verb>} with the run's own cache and state directories. */
    private static List<String> base(Path dir, String verb) {
        return List.of(
                "tool",
                verb,
                "--cache-dir",
                dir.resolve("home/cache").toString(),
                "--state-dir",
                dir.resolve("home").toString());
    }

    private static int toolRun(Path dir, Path script, String... args) {
        List<String> argv = new ArrayList<>(base(dir, "run"));
        argv.add(script.toString());
        argv.addAll(List.of(args));
        return run(argv.toArray(String[]::new));
    }

    /** Every compiled script's marker under the run's cache. */
    private static List<Path> markers(Path dir) throws IOException {
        Path cache = dir.resolve("home/cache/tool-scripts");
        if (!Files.isDirectory(cache)) return List.of();
        try (Stream<Path> entries = Files.list(cache)) {
            return entries.map(e -> e.resolve("classes/META-INF/jk-script"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    private static Path onlyMarker(Path dir) throws IOException {
        List<Path> all = markers(dir);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    /** Stamp {@code file} a clear minute into the past, so any rewrite is detectable. */
    private static long backdate(Path file) throws IOException {
        long past = Files.getLastModifiedTime(file).toMillis() - 60_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(past));
        return past;
    }

    /** Run an installed launcher and return its exit code. */
    private static int launch(Path bin, String name, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        if (Os.isWindows()) {
            cmd.addAll(List.of("cmd.exe", "/c", bin.resolve(name + ".cmd").toString()));
        } else {
            cmd.add(bin.resolve(name).toString());
        }
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    /** A jar holding {@code com.example.GroovyGreeter}, whose {@code exitCode()} is 17. */
    private static Path greeterJar(Path dir) throws Exception {
        Path src =
                Files.createDirectories(dir.resolve("greeter-src/com/example")).resolve("GroovyGreeter.java");
        Files.writeString(src, """
                package com.example;
                public final class GroovyGreeter {
                    private GroovyGreeter() {}
                    public static int exitCode() { return 17; }
                }
                """, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(dir.resolve("greeter-classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, "-d", classes.toString(), src.toString()))
                .isZero();
        Path jar = dir.resolve("greeter-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("com/example/GroovyGreeter.class"));
            out.write(Files.readAllBytes(classes.resolve("com/example/GroovyGreeter.class")));
            out.closeEntry();
        }
        return jar;
    }
}
