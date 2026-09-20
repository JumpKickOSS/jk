// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.mavenPath;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.testing.TestCaches;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk tool run <file>} — file-execution modes (.java/.kt/.kts/.jar). (Coordinate-target
 * behavior lives alongside the tool-install integration tests.) These moved here from {@code
 * RunCommandTest} when {@code jk run} stopped interpreting file arguments.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class ToolRunCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @Test
    void runs_a_solo_script_with_no_deps(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Solo.java");
        Files.writeString(script, """
                public class Solo {
                    public static void main(String[] args) {
                        System.exit(0);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(0);

        // Cache dir was populated.
        Path scriptCache = tempDir.resolve("home/script-cache");
        assertThat(scriptCache).exists();
        assertThat(Files.list(scriptCache).findAny()).isPresent();
    }

    @Test
    void jbang_alias_resolves_the_catalog_and_runs_the_script(@TempDir Path tempDir) throws Exception {
        maven.served().put("/cat/jbang-catalog.json", """
                {
                  "aliases": {
                    "hello": {
                      "script-ref": "scripts/Hello.java",
                      "arguments": ["seed"]
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
        maven.served().put("/cat/scripts/Hello.java", """
                public class Hello {
                    public static void main(String[] args) { System.exit(args.length); }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path state = tempDir.resolve("home");
        run("trust", "add", "--state-dir", state.toString(), maven.base().toString() + "/");
        String host = maven.base().getAuthority() + maven.base().getPath();
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                state.toString(),
                "hello@" + host + "/cat",
                "extra");
        // alias default arguments (["seed"]) + CLI args (["extra"]) reach the script.
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void jbang_alias_dependencies_and_java_options_are_honored(@TempDir Path tempDir) throws Exception {
        maven.servePom("com.example", "greeter", "1.0.0");
        maven.served()
                .put(mavenPath("com.example", "greeter", "1.0.0", "jar"), Files.readAllBytes(buildGreeterJar(tempDir)));
        maven.served().put("/cat/jbang-catalog.json", """
                {
                  "aliases": {
                    "probe": {
                      "script-ref": "Probe.java",
                      "dependencies": ["com.example:greeter:1.0.0"],
                      "java-options": ["-Dprobe.flag=on"]
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
        maven.served().put("/cat/Probe.java", """
                public class Probe {
                    public static void main(String[] args) {
                        // The alias dep must be on the classpath (Greeter.exitCode() = 17)
                        // and its java-option set — exit 0 only when both hold.
                        boolean dep = com.example.Greeter.exitCode() == 17;
                        boolean opt = "on".equals(System.getProperty("probe.flag"));
                        System.exit(dep && opt ? 0 : 3);
                    }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path state = tempDir.resolve("home");
        run("trust", "add", "--state-dir", state.toString(), maven.base().toString() + "/");
        String host = maven.base().getAuthority() + maven.base().getPath();
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                state.toString(),
                "--repo-url",
                maven.base().toString(),
                "probe@" + host + "/cat");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void jbang_alias_with_unknown_name_reports_the_catalog(@TempDir Path tempDir) throws Exception {
        maven.served().put("/cat/jbang-catalog.json", "{ \"aliases\": {} }".getBytes(StandardCharsets.UTF_8));
        Path state = tempDir.resolve("home");
        run("trust", "add", "--state-dir", state.toString(), maven.base().toString() + "/");
        String host = maven.base().getAuthority() + maven.base().getPath();
        int exit = run("tool", "run", "--state-dir", state.toString(), "nope@" + host + "/cat");
        assertThat(exit).isEqualTo(70); // rendered IOException: catalog has no such alias
    }

    @Test
    void git_target_clones_and_runs_a_jbang_convention_repo(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("main.java"), """
                public class main {
                    public static void main(String[] args) { System.exit(args.length); }
                }
                """, StandardCharsets.UTF_8);
        git(repo, "init", "-b", "main");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "init");

        Path state = tempDir.resolve("home");
        run("trust", "add", "--state-dir", state.toString(), tempDir.toUri().toString());
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                state.toString(),
                "git+" + repo.toUri(),
                "x");
        assertThat(exit).isEqualTo(1); // args reach the repo's main.java
    }

    @Test
    void git_target_with_subdir_runs_that_directory(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("mono");
        Files.createDirectories(repo.resolve("tools/greeter"));
        Files.writeString(repo.resolve("tools/greeter/main.java"), """
                public class main {
                    public static void main(String[] args) { System.exit(0); }
                }
                """, StandardCharsets.UTF_8);
        git(repo, "init", "-b", "main");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "init");

        Path state = tempDir.resolve("home");
        run("trust", "add", "--state-dir", state.toString(), tempDir.toUri().toString());
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                state.toString(),
                "git+" + repo.toUri() + "!tools/greeter");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void untrusted_git_target_is_rejected(@TempDir Path tempDir) throws Exception {
        int exit = run(
                "tool",
                "run",
                "--state-dir",
                tempDir.resolve("home").toString(),
                "git+" + tempDir.resolve("nope").toUri());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void untrusted_url_is_rejected_with_the_trust_hint(@TempDir Path tempDir) throws Exception {
        maven.served().put("/scripts/Remote.java", """
                public class Remote {
                    public static void main(String[] args) { System.exit(0); }
                }
                """.getBytes(StandardCharsets.UTF_8));

        // Non-interactive (test JVM has no TTY): hard error naming `jk trust add`.
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                maven.base() + "/scripts/Remote.java");
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void trusted_url_downloads_and_runs(@TempDir Path tempDir) throws Exception {
        maven.served().put("/scripts/Remote.java", """
                public class Remote {
                    public static void main(String[] args) { System.exit(args.length); }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path state = tempDir.resolve("home");
        assertThat(run(
                        "trust",
                        "add",
                        "--state-dir",
                        state.toString(),
                        maven.base().toString() + "/"))
                .isEqualTo(0);
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                state.toString(),
                maven.base() + "/scripts/Remote.java",
                "a",
                "b");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void url_script_pulls_its_sources_siblings(@TempDir Path tempDir) throws Exception {
        maven.served().put("/x/Main.java", """
                //SOURCES Helper.java
                public class Main {
                    public static void main(String[] args) { System.exit(Helper.code()); }
                }
                """.getBytes(StandardCharsets.UTF_8));
        maven.served().put("/x/Helper.java", """
                public class Helper { static int code() { return 0; } }
                """.getBytes(StandardCharsets.UTF_8));

        Path state = tempDir.resolve("home");
        run("trust", "add", "--state-dir", state.toString(), maven.base().toString() + "/");
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                state.toString(),
                maven.base() + "/x/Main.java");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void directory_with_main_java_runs_the_jbang_folder_convention(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("app");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("main.java"), """
                public class main {
                    public static void main(String[] args) { System.exit(0); }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                dir.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void directory_with_a_single_script_runs_it(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("gist");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Only.java"), """
                public class Only {
                    public static void main(String[] args) { System.exit(0); }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                dir.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void ambiguous_directory_is_a_usage_error(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("multi");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.java"), "public class A {}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("B.java"), "public class B {}", StandardCharsets.UTF_8);

        int exit = run("tool", "run", dir.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void directory_with_jk_toml_builds_and_execs_the_project(@TempDir Path tempDir) throws Exception {
        // `jk tool run <dir>` on a jk project == `jk run` without the cd.
        run(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "traditional",
                tempDir.toString());
        Path src = tempDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.exit(args.length); }
                }
                """);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                TestCaches.dir("shared-cache").toString(),
                tempDir.toString(),
                "--",
                "a",
                "b");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void sources_directive_compiles_companion_files(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Helper.java"), """
                public class Helper {
                    static int code() { return 0; }
                }
                """, StandardCharsets.UTF_8);
        Path script = tempDir.resolve("Main.java");
        Files.writeString(script, """
                //SOURCES Helper.java
                public class Main {
                    public static void main(String[] args) {
                        System.exit(Helper.code());
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void files_directive_puts_resources_on_the_runtime_classpath(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("greeting.txt"), "ok", StandardCharsets.UTF_8);
        Path script = tempDir.resolve("Res.java");
        Files.writeString(script, """
                //FILES msg/greeting.txt=greeting.txt
                public class Res {
                    public static void main(String[] args) throws Exception {
                        System.exit(Res.class.getResourceAsStream("/msg/greeting.txt") != null ? 0 : 1);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void main_directive_overrides_the_filename_convention(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Wrapper.java");
        Files.writeString(script, """
                //MAIN Entry
                public class Wrapper {
                    public static void main(String[] args) { System.exit(1); }
                }
                class Entry {
                    public static void main(String[] args) { System.exit(0); }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void exit_code_is_propagated_from_script(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Fail.java");
        Files.writeString(script, """
                public class Fail {
                    public static void main(String[] args) {
                        System.exit(42);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(42);
    }

    @Test
    void script_args_are_forwarded(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Echo.java");
        Files.writeString(script, """
                public class Echo {
                    public static void main(String[] args) {
                        // Use the count of args as the exit code so the test can read it.
                        System.exit(args.length);
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString(),
                "one",
                "two",
                "three");
        assertThat(exit).isEqualTo(3);
    }

    @Test
    void resolves_dep_from_header(@TempDir Path tempDir) throws Exception {
        // Build a tiny "Greeter" jar that exposes a method our script will call.
        maven.servePom("com.example", "greeter", "1.0.0");
        maven.served()
                .put(mavenPath("com.example", "greeter", "1.0.0", "jar"), Files.readAllBytes(buildGreeterJar(tempDir)));

        Path script = tempDir.resolve("CallGreeter.java");
        Files.writeString(script, """
                //jk dep com.example:greeter:1.0.0

                public class CallGreeter {
                    public static void main(String[] args) {
                        // Greeter.exitCode() returns 17 — propagate as our exit code.
                        System.exit(com.example.Greeter.exitCode());
                    }
                }
                """, StandardCharsets.UTF_8);

        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                "--repo-url",
                maven.base().toString(),
                script.toString());
        assertThat(exit).isEqualTo(17);
    }

    @Test
    void second_run_uses_cached_classes(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Cached.java");
        Files.writeString(script, """
                public class Cached {
                    public static void main(String[] args) { System.exit(0); }
                }
                """, StandardCharsets.UTF_8);

        int firstExit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(firstExit).isEqualTo(0);

        // Find the cache directory created by the first run.
        Path scriptCache = tempDir.resolve("home/script-cache");
        Path hashDir = Files.list(scriptCache).findFirst().orElseThrow();
        Path classFile = hashDir.resolve("classes/Cached.class");
        // Backdate the class file a clear minute, so ANY recompile moves its mtime. The
        // `Thread.sleep(50)` this replaces was betting 50ms exceeds the filesystem's mtime
        // granularity — a fact about the machine, not about the cache.
        long firstMtime = backdate(classFile);

        // Re-run without --force-recompile; classes should not be rewritten.
        int secondExit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(secondExit).isEqualTo(0);
        long secondMtime = Files.getLastModifiedTime(classFile).toMillis();
        assertThat(secondMtime).isEqualTo(firstMtime);
    }

    @Test
    void force_recompile_flag_invalidates_cache(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Forced.java");
        Files.writeString(script, """
                public class Forced { public static void main(String[] args) {} }
                """, StandardCharsets.UTF_8);

        run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        Path scriptCache = tempDir.resolve("home/script-cache");
        Path classFile = Files.list(scriptCache).findFirst().orElseThrow().resolve("classes/Forced.class");
        long firstMtime = backdate(classFile);

        run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                "--force",
                script.toString());
        long secondMtime = Files.getLastModifiedTime(classFile).toMillis();
        assertThat(secondMtime).isGreaterThan(firstMtime);
    }

    @Test
    void missing_script_returns_no_input(@TempDir Path tempDir) {
        int exit = run("tool", "run", tempDir.resolve("missing.java").toString());
        assertThat(exit).isEqualTo(66);
    }

    @Test
    void compile_failure_returns_nonzero(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("Broken.java");
        Files.writeString(script, "public class Broken { not java }\n");
        int exit = run(
                "tool",
                "run",
                "--cache-dir",
                tempDir.resolve("home/cache").toString(),
                "--state-dir",
                tempDir.resolve("home").toString(),
                script.toString());
        assertThat(exit).isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Stamp {@code file} a clear minute into the past and return that mtime, so a later rewrite is
     * detectable regardless of the filesystem's timestamp granularity.
     */
    private static long backdate(Path file) throws IOException {
        long past = System.currentTimeMillis() - 60_000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis(past));
        return Files.getLastModifiedTime(file).toMillis();
    }

    /**
     * Build a jar containing {@code com.example.Greeter} with an {@code exitCode()} method returning
     * 17, used by the dep-resolution test as something the script can call.
     */
    private static Path buildGreeterJar(Path tempDir) throws Exception {
        Path src = tempDir.resolve("Greeter.java");
        Files.writeString(src, """
                package com.example;
                public final class Greeter {
                    private Greeter() {}
                    public static int exitCode() { return 17; }
                }
                """);
        Path out = tempDir.resolve("greeter-classes");
        Files.createDirectories(out);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-d", out.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("compile of Greeter failed");
        Path classFile = out.resolve("com/example/Greeter.class");

        Path jar = tempDir.resolve("greeter.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("com/example/Greeter.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }

    /** Drive the system git for local-repo fixtures (identity + signing pinned for hermeticity). */
    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "git",
                "-C",
                dir.toString(),
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@t",
                "-c",
                "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + out);
        }
    }
}
