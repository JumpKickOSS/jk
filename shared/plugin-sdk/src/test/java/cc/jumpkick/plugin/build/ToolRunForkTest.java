// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fork half of {@link TaskExec.ToolRun}: {@code start()} is the one {@code ProcessBuilder} in
 * the plugin family, and {@code run()} / {@code stream()} are drains over it (JK-2432).
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
        assertThat(probe().env(Map.of(EnvEchoMain.VAR, "from-a-table")).run().output()).contains("env=from-a-table");
        // Additive, not replacing: PATH is still there, so the child is not run in a bare
        // environment (which is how a tool that shells out starts failing on some machines only).
        assertThat(probe().env(EnvEchoMain.VAR, "x").arg("--path").run().output()).doesNotContain("path=<unset>");
    }

    @Test
    void start_hands_back_the_process_for_a_caller_that_drives_its_own_drain() throws Exception {
        Process process = probe().arg("solo").start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("arg=solo");
    }

    /** {@code java -cp <test classpath> EnvEchoMain} on the JVM running the test. */
    private static TaskExec.ToolRun probe() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return new TaskExec.ToolRun(JdkFingerprint.tool(javaHome, "java"))
                .arg("-cp")
                .arg(System.getProperty("java.class.path"))
                .arg(EnvEchoMain.class.getName());
    }
}
