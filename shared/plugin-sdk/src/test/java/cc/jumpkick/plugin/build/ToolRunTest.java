// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JdkFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TaskExec.ToolRun#command()} — the one assembly {@code run()} forks, pinned without
 * forking anything. Twenty production call sites across seven plugins go through this class.
 *
 * <p>Honesty note on the head pin: on Linux a hand-built {@code <javaHome>/bin/<tool>} produces
 * the same string as {@link JdkFingerprint#tool}, so the agree-with-owner assertion distinguishes
 * the {@code .exe} half only on Windows (fail-closed-style indistinguishability). Its value here
 * is catching a <em>divergent</em> replacement — a second resolver, a dropped segment; G1 and the
 * owner's own tests carry the suffix shape.
 */
class ToolRunTest {

    @Test
    void named_tool_head_agrees_with_the_owner(@TempDir Path tmp) {
        Path javaHome = tmp.resolve("jdk");
        List<String> command = new TaskExec.ToolRun(javaHome, "javac").command();
        assertThat(command.get(0))
                .isEqualTo(JdkFingerprint.tool(javaHome, "javac").toString());
    }

    @Test
    void explicit_executable_head_is_the_absolute_path(@TempDir Path tmp) {
        Path exe = tmp.resolve("tools").resolve("aapt2");
        List<String> command = new TaskExec.ToolRun(exe).command();
        assertThat(command.get(0)).isEqualTo(exe.toAbsolutePath().toString());
    }

    @Test
    void classpath_contributes_cp_pair_joined_by_the_owner(@TempDir Path tmp) {
        List<Path> entries = List.of(tmp.resolve("a.jar"), tmp.resolve("lib"), tmp.resolve("b.jar"));
        List<String> command = new TaskExec.ToolRun(tmp.resolve("jdk"), "java")
                .classpath(entries)
                .command();
        assertThat(command.subList(1, 3)).containsExactly("-cp", Classpaths.join(entries));
    }

    @Test
    void args_keep_call_order_after_the_head(@TempDir Path tmp) {
        List<Path> cp = List.of(tmp.resolve("a.jar"));
        List<String> command = new TaskExec.ToolRun(tmp.resolve("jdk"), "java")
                .classpath(cp)
                .mainClass("demo.Main")
                .arg("--first")
                .args(List.of("--second", "--third"))
                .arg("--last")
                .command();
        assertThat(command)
                .containsExactly(
                        JdkFingerprint.tool(tmp.resolve("jdk"), "java").toString(),
                        "-cp",
                        Classpaths.join(cp),
                        "demo.Main",
                        "--first",
                        "--second",
                        "--third",
                        "--last");
    }

    /** A forked tool that prompts reads end of input at once rather than waiting on a pipe nobody writes. */
    @Test
    @Timeout(20)
    void a_tool_that_reads_stdin_sees_end_of_input() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "a POSIX shell");
        List<String> lines = new ArrayList<>();
        int exit = new TaskExec.ToolRun(Path.of("/bin/sh"))
                .args(List.of("-c", "read answer || echo eof")).stream(lines::add);
        assertThat(exit).isZero();
        assertThat(lines).containsExactly("eof");
    }
}
