// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command body forks JDK tools off the <em>build's</em> JDK, through the same
 * {@link TaskExec.ToolRun} a step and a packager get.
 *
 * <p>{@link PluginCommandExec} had neither {@code javaHome()} nor {@code tool()}, so android's five
 * command-side launchers read {@code System.getProperty("java.home")} — which inside a forked
 * worker is the <em>engine's</em> floor JDK, not the project's pin. The distinguishing assertion
 * below is therefore that the resolved tool head sits under the spec's JDK and <em>not</em> under
 * the JVM running the test: an accessor that quietly fell back to the worker's own {@code java.home}
 * would satisfy "a path to keytool" and fail this.
 */
class CommandExecToolTest {

    /** A fixture command that reports what its exec surface says the JDK is, and what it resolves. */
    private static final BuildPlugin FIXTURE = ctx -> ctx.command(PluginCommandSpec.named("probe")
            .description("report the resolved tool head")
            .run(exec -> {
                exec.out("javaHome=" + exec.javaHome());
                exec.out("keytool=" + exec.tool("keytool").command().get(0));
                exec.out("java=" + exec.java().command().get(0));
                return 0;
            }));

    @Test
    void a_command_forks_off_the_specs_jdk_not_the_workers_own(@TempDir Path dir) throws Exception {
        Path pinned = Files.createDirectories(dir.resolve("pinned-jdk"));
        var out = capture();

        int exit = BuildPluginHarness.run(FIXTURE, List.of(spec(dir, pinned).toString()), out.writer);

        assertThat(exit).isZero();
        // Assert on decoded payloads: wire lines JSON-escape '\\', so Path.toString() never
        // substring-matches the raw protocol text on Windows.
        List<String> payloads = commandOuts(out);
        assertThat(payloads)
                .contains(
                        "javaHome=" + pinned,
                        "keytool=" + JdkFingerprint.tool(pinned, "keytool"),
                        "java=" + JdkFingerprint.tool(pinned, "java"));
        // The half that a silent fallback would pass: nothing resolved against the JVM we run on.
        Path running = Path.of(Objects.requireNonNull(System.getProperty("java.home"), "java.home"));
        assertThat(payloads).noneMatch(l -> l.contains(running.toString()));
    }

    /**
     * Absent is loud, not defaulted. A command spec with no {@code java-home} is an engine bug, and
     * the plausible fallback is invisibly wrong — which is why this direction is asserted rather
     * than left to the fail-closed reasoning {@code offline} uses (there is no safe guess for a
     * JDK, so there is no default to make absent and stated indistinguishable).
     */
    @Test
    void a_command_spec_with_no_jdk_fails_loudly(@TempDir Path dir) throws Exception {
        var out = capture();
        Path spec = dir.resolve("no-jdk.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "probe", "fx")
                        .commandArgs(List.of())
                        .lines(),
                StandardCharsets.UTF_8);

        int exit = BuildPluginHarness.run(FIXTURE, List.of(spec.toString()), out.writer);

        assertThat(exit).isEqualTo(1);
        assertThat(out.lines()).anyMatch(l -> l.contains("command-failed") && l.contains("states no JDK"));
    }

    private static Path spec(Path dir, Path javaHome) throws Exception {
        Path spec = dir.resolve("probe.spec");
        return Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "probe", "fx")
                        .javaHome(javaHome)
                        .commandArgs(List.of())
                        .lines(),
                StandardCharsets.UTF_8);
    }

    private static Captured capture() {
        var buffer = new ByteArrayOutputStream();
        return new Captured(buffer, new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:"));
    }

    /** Decoded {@code line} values from prefixed {@code command-out} replies. */
    private static List<String> commandOuts(Captured out) {
        return out.lines().stream()
                .filter(l -> l.contains("\"t\":\"command-out\""))
                .map(l -> Objects.requireNonNull(Jsonl.str(l.substring(l.indexOf('{')), "line"), "command-out.line"))
                .toList();
    }

    private record Captured(ByteArrayOutputStream buffer, ProtocolWriter writer) {
        List<String> lines() {
            return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
        }
    }
}
