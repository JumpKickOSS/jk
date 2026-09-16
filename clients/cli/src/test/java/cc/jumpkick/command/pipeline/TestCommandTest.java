// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.command.ScaffoldTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class TestCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @Test
    void test_with_no_test_sources_passes(@TempDir Path tempDir) throws Exception {
        scaffoldNoDeps(tempDir);
        int exit = run(
                "test",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void guard_is_inner_when_there_is_no_integration_suite(@TempDir Path tempDir) throws Exception {
        scaffoldNoDeps(tempDir);
        String cache = tempDir.resolve("cache").toString();
        int inner = run("test", "-C", tempDir.toString(), "--cache-dir", cache);
        int guard = run("test", "--guard", "-C", tempDir.toString(), "--cache-dir", cache);
        assertThat(inner).isEqualTo(0);
        assertThat(guard).isEqualTo(0);
    }

    @Test
    void guard_compiles_integration_when_present(@TempDir Path tempDir) throws Exception {
        scaffoldNoDeps(tempDir);
        // Force traditional layout so src/integration/ is a suite, not main sources.
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Path broken = tempDir.resolve("src/integration/java/example/BrokenIT.java");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "package example;\nclass BrokenIT { void t(  // syntax error\n");
        String cache = tempDir.resolve("cache").toString();
        assertThat(run("test", "-C", tempDir.toString(), "--cache-dir", cache)).isEqualTo(0);
        assertThat(run("test", "--guard", "-C", tempDir.toString(), "--cache-dir", cache))
                .isNotEqualTo(0);
    }

    @Test
    void gate_script_runs_on_gate_and_scripts_only_not_on_inner_test(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("proj");
        Path cache = tempDir.resolve("cache");
        Path ran = tempDir.resolve("gate-ran.log");
        scaffoldNoDeps(project);
        Files.createDirectories(project.resolve("src/main/java/example"));
        Files.writeString(
                project.resolve("src/main/java/example/App.java"),
                "package example; public class App { public static void main(String[] a) {} }\n");
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve(".jk/guard.groovy"),
                "new File('" + ran.toString().replace("\\", "\\\\") + "').append('x')\n");
        String c = cache.toString();
        String p = project.toString();
        assertThat(run("test", "-C", p, "--cache-dir", c)).isEqualTo(0);
        assertThat(Files.exists(ran)).isFalse();
        assertThat(run("test", "--guard", "-C", p, "--cache-dir", c)).isEqualTo(0);
        assertThat(Files.readString(ran)).hasSize(1);
        assertThat(run("test", "--guard", "--no-scripts", "-C", p, "--cache-dir", c))
                .isEqualTo(0);
        assertThat(Files.readString(ran)).hasSize(1);
        assertThat(run("test", "--scripts-only", "-C", p, "--cache-dir", c)).isEqualTo(0);
        assertThat(Files.readString(ran)).hasSize(1);
        Files.writeString(
                project.resolve("src/main/java/example/App.java"),
                "package example; public class App { public static int n() { return 1; } }\n");
        assertThat(run("test", "--scripts-only", "-C", p, "--cache-dir", c)).isEqualTo(0);
        assertThat(Files.readString(ran)).hasSize(2);
        assertThat(run("test", "--guard", "--scripts-only", "-C", p, "--cache-dir", c))
                .isEqualTo(0);
        assertThat(Files.readString(ran)).hasSize(2);
    }

    @Test
    void planted_gate_throw_fails_and_is_a_failed_task(@TempDir Path tempDir) throws Exception {
        scaffoldNoDeps(tempDir);
        Files.createDirectories(tempDir.resolve("src/main/java/example"));
        Files.writeString(
                tempDir.resolve("src/main/java/example/App.java"),
                "package example; public class App { public static void main(String[] a) {} }\n");
        Files.createDirectories(tempDir.resolve(".jk"));
        Files.writeString(tempDir.resolve(".jk/guard.groovy"), "throw new IllegalStateException('planted-gate')\n");
        String cache = tempDir.resolve("cache").toString();
        assertThat(run("test", "--scripts-only", "-C", tempDir.toString(), "--cache-dir", cache))
                .isNotEqualTo(0);
        Path results = tempDir.resolve("target/jk-results.md");
        assertThat(results).exists();
        String md = Files.readString(results);
        assertThat(md).contains("FAIL");
        assertThat(md).containsIgnoringCase("guard");
    }

    @Test
    void workspace_root_gate_script_is_once_per_graph(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("ws");
        Path cache = tempDir.resolve("cache");
        Path ran = tempDir.resolve("gate-ran.log");
        Files.createDirectories(project.resolve("core/src/main/java/example"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "0.1.0"
                jdk = "25"
                java = 25

                [workspace]
                modules = ["core"]
                """);
        Files.writeString(
                project.resolve("core/jk.toml"),
                "group = \"com.example\"\nname = \"core\"\nversion = \"0.1.0\"\njdk = \"25\"\njava = 25\n");
        Files.writeString(
                project.resolve("core/src/main/java/example/App.java"),
                "package example; public class App { public static void main(String[] a) {} }\n");
        ScaffoldTestSupport.writeEmptyLock(project);
        ScaffoldTestSupport.writeEmptyLock(project.resolve("core"));
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve(".jk/guard.groovy"),
                "new File('" + ran.toString().replace("\\", "\\\\") + "').append('x')\n");
        String c = cache.toString();
        String p = project.toString();
        // The workspace has no suite, so each `jk test` that runs the suites is `no tests ran` (exit 2).
        assertThat(run("test", "-C", p, "--cache-dir", c)).isEqualTo(2);
        assertThat(Files.exists(ran)).isFalse();
        assertThat(run("build", "-C", p, "--cache-dir", c, "--skip-tests")).isEqualTo(0);
        assertThat(Files.exists(ran)).isFalse();
        assertThat(run("test", "--guard", "-C", p, "--cache-dir", c)).isEqualTo(2);
        assertThat(Files.readString(ran)).hasSize(1);
        assertThat(run("test", "--scripts-only", "-C", p, "--cache-dir", c)).isEqualTo(0);
        assertThat(Files.readString(ran)).hasSize(1);
    }

    // (Removed test_without_lockfile_errors: `jk test` no longer requires a
    // pre-existing jk-lock.toml — the plan auto-locks like `jk build`/`run`.
    // That auto-lock path is covered by the build/run integration tests.)

    // A genuinely test-source-free project: bare manifest, no sources. (`jk new`
    // now scaffolds a sample CalcTest, so it can't stand in for "no tests".)
    private static void scaffoldNoDeps(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("jk.toml"),
                "group = \"com.example\"\nname = \"x\"\nversion = \"0.1.0\"\njdk = \"25\"\njava = 25\n");
        ScaffoldTestSupport.writeEmptyLock(dir); // jk test needs a lock; nothing to resolve
    }
}
