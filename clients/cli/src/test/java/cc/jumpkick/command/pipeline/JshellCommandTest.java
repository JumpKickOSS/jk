// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.command.ScaffoldTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class JshellCommandTest {

    static boolean jshellAvailable() {
        return JshellCommand.findJshell() != null;
    }

    @Test
    @EnabledIf("jshellAvailable")
    void jshell_batch_stdin_exits_zero(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello { public static int x = 7; }\n");
        Path cache = tempDir.resolve("cache");
        assertThat(run(
                        "build",
                        "-C",
                        tempDir.toString(),
                        "--cache-dir",
                        cache.toString(),
                        "--skip-tests",
                        "--no-timeline"))
                .isZero();

        // Drive jshell non-interactively via the same binary jk would use, with stdin.
        // (Jk.execute does not pipe stdin; this still validates the jshell + classes path.)
        Path classes = tempDir.resolve("target/classes/main");
        assertThat(Files.isDirectory(classes)).isTrue();
        Path jshell = JshellCommand.findJshell();
        assertThat(jshell).as("jshell beside the test JVM").isNotNull();
        List<String> cmd = new ArrayList<>();
        cmd.add(Objects.requireNonNull(jshell).toString());
        cmd.add("--class-path");
        cmd.add(classes.toString());
        cmd.add("-q");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write("example.Hello.x\n/exit\n".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    @Test
    void hasExecutionSpec_detects_user_override() {
        assertThat(JshellCommand.hasExecutionSpec(List.of())).isFalse();
        assertThat(JshellCommand.hasExecutionSpec(List.of("-q"))).isFalse();
        assertThat(JshellCommand.hasExecutionSpec(List.of("--execution", "jdi")))
                .isTrue();
        assertThat(JshellCommand.hasExecutionSpec(List.of("--execution=local"))).isTrue();
    }

    @Test
    void jshell_missing_classes_with_no_build_fails(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir);
        int exit = run("jshell", "--no-build", "-C", tempDir.toString());
        assertThat(exit).isNotZero();
    }

    @Test
    void jshell_help_lists_command() {
        int exit = Jk.execute("jshell", "--help");
        assertThat(exit).isZero();
    }
}
