// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        // start() merges the child's stderr into its stdout; the builder says so to the drain.
        ProcessBuilder merged = new ProcessBuilder().redirectErrorStream(true);
        Processes.Captured run = Processes.capture(merged, probe().arg("solo").start(), Duration.ofMinutes(1));
        assertThat(run.exit()).isZero();
        assertThat(run.output()).contains("arg=solo");
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

    /**
     * A command line past the argfile threshold (a long classpath, a list of class files) reaches
     * the child whole and unchanged on every OS, well under Windows' 32 K command-line cap.
     */
    @Test
    void a_command_too_long_for_one_command_line_reaches_the_child_intact() throws Exception {
        List<String> args = new ArrayList<>(List.of("with space", "with \"quote\"", "C:\\dir\\file.jar", "#hash"));
        while (args.size() < 400) args.add("x".repeat(100) + args.size());

        TaskExec.ToolRun run = probe();
        for (String arg : args) run.arg(arg);
        TaskExec.ToolRun.Result result = run.run();

        assertThat(String.join(" ", run.command()).length()).isGreaterThan(32_767);
        assertThat(result.exit()).isZero();
        List<String> expected = new ArrayList<>();
        for (String arg : args) expected.add("arg=" + arg);
        expected.add("env=<unset>");
        assertThat(result.output().lines()).containsExactlyElementsOf(expected);
    }

    /**
     * Past the argfile threshold, an argument the system encoding cannot hold is refused by name:
     * {@code java} reads its command line in that encoding too, so forking the whole line instead
     * would pass the argument mangled, or overflow Windows' 32 K cap.
     */
    @Test
    void a_long_command_with_an_argument_the_system_encoding_cannot_hold_is_refused_by_name() {
        String unreadable = "lib/日本.jar";
        Charset system = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        assumeFalse(system.newEncoder().canEncode(unreadable), "the system encoding holds every character");
        TaskExec.ToolRun run = probe();
        run.arg(String.join(Classpaths.SEPARATOR, "a.jar", unreadable, "b.jar"));
        while (String.join(" ", run.command()).length() < 32_767) run.arg("x".repeat(100));

        assertThatThrownBy(run::run)
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(unreadable + ": java")
                .hasMessageContaining(system.name());
    }

    /**
     * A short command is refused the same way: the {@code java} launcher decodes its command line
     * in the system encoding whatever its length, so the argument would reach it mangled and fail
     * later as a class not found.
     */
    @Test
    void a_short_command_with_an_argument_the_system_encoding_cannot_hold_is_refused_by_name() {
        String unreadable = "lib/日本.jar";
        TaskExec.ToolRun run = probe().arg(String.join(Classpaths.SEPARATOR, "a.jar", unreadable, "b.jar"));

        // An ANSI-like system encoding on every host, read at the fork.
        String saved = System.getProperty("native.encoding");
        System.setProperty("native.encoding", "US-ASCII");
        try {
            assertThatThrownBy(run::run)
                    .isInstanceOf(IOException.class)
                    .hasMessageStartingWith(unreadable + ": java")
                    .hasMessageContaining("US-ASCII");
        } finally {
            if (saved == null) {
                System.clearProperty("native.encoding");
            } else {
                System.setProperty("native.encoding", saved);
            }
        }
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
