// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fork half of {@link TaskExec.ToolRun}: {@code start()} is the one {@code ProcessBuilder} in
 * the plugin family, and {@code run()} / {@code stream()} are drains over it.
 *
 * <p>{@link ToolRunTest} pins the argv without forking; these cases fork the JVM running the test,
 * because the three knobs the sweep needed — a child environment, line-at-a-time draining, and
 * "hand me the {@link Process}, I have my own timeout" — are only observable in a real child.
 * Before this, two plugins re-implemented the drain and one built its own {@code ProcessBuilder}
 * purely to set two environment variables.
 */
class ToolRunForkTest {

    @Test
    void stream_delivers_lines_in_order_and_returns_the_exit_code() throws Exception {
        List<String> seen = new ArrayList<>();
        int exit = probe().arg("one").arg("two").arg("three").stream(seen::add);

        assertThat(exit).isZero();
        assertThat(seen).containsExactly("arg=one", "arg=two", "arg=three", "env=<unset>");
    }

    @Test
    void run_buffers_the_same_lines_and_carries_the_childs_exit_code() throws Exception {
        TaskExec.ToolRun.Result ok = probe().arg("hi").run();
        assertThat(ok.exit()).isZero();
        assertThat(ok.output()).isEqualTo("arg=hi\nenv=<unset>\n");

        // `--fail` makes the probe exit 7: a drain that swallowed the status would read as success.
        assertThat(probe().arg("--fail").run().exit()).isEqualTo(7);
    }

    @Test
    void env_reaches_the_child_and_adds_to_the_inherited_environment() throws Exception {
        assertThat(probe().env(EnvEchoMain.VAR, "from-a-map").run().output()).contains("env=from-a-map");
        assertThat(probe().env(Map.of(EnvEchoMain.VAR, "from-a-table")).run().output())
                .contains("env=from-a-table");
        // Additive, not replacing: PATH is still there, so the child is not run in a bare
        // environment (which is how a tool that shells out starts failing on some machines only).
        assertThat(probe().env(EnvEchoMain.VAR, "x").arg("--path").run().output())
                .doesNotContain("path=<unset>");
    }

    @Test
    void start_hands_back_the_process_for_a_caller_that_drives_its_own_drain() throws Exception {
        Process process = probe().arg("solo").start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("arg=solo");
    }

    @Test
    void start_with_a_file_redirect_appends_the_childs_output_to_that_file(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("child.log");
        Files.writeString(log, "kept\n", StandardCharsets.UTF_8);

        Process process = probe().arg("logged").start(ProcessBuilder.Redirect.appendTo(log.toFile()));

        assertThat(process.waitFor()).isZero();
        assertThat(process.getInputStream().read())
                .as("nothing is piped back: the output went to the file")
                .isEqualTo(-1);
        assertThat(Files.readString(log, StandardCharsets.UTF_8)).isEqualTo("kept\narg=logged\nenv=<unset>\n");
    }

    /** {@code java -cp <test classpath> EnvEchoMain} on the JVM running the test. */
    private static TaskExec.ToolRun probe() {
        Path javaHome = Path.of(Objects.requireNonNull(System.getProperty("java.home"), "java.home"));
        return new TaskExec.ToolRun(JdkFingerprint.tool(javaHome, "java"))
                .arg("-cp")
                .arg(Objects.requireNonNull(System.getProperty("java.class.path"), "java.class.path"))
                .arg(EnvEchoMain.class.getName());
    }
}
