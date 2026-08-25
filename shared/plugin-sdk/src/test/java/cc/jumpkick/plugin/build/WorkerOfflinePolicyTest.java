// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The job's network policy reaches the <em>body</em> of a step, a command and a packager — the
 * three worker-side exec surfaces — off the spec and nothing else.
 *
 * <p>Asserting on the body's view rather than on the spec text is the point: the claim under test
 * is "a worker sees offline", and a spec line that no decoder reads would satisfy a text assertion
 * while the worker went on uploading.
 *
 * <p>The third case is the one that matters most. A spec with no {@link PluginProtocol#OFFLINE}
 * line reads back as offline, so the failure mode of a fork path that forgets to stamp it is a
 * refusal a developer sees immediately, not silent egress a user never sees.
 */
class WorkerOfflinePolicyTest {

    /** Reports what each surface was told, on the wire, as {@code offline=<bool>}. */
    private static final BuildPlugin FIXTURE = ctx -> {
        ctx.task(TaskSpec.named("probe-step").run(exec -> exec.label("offline=" + exec.offline())));
        ctx.command(PluginCommandSpec.named("probe-command").description("report the policy").run(exec -> {
            exec.out("offline=" + exec.offline());
            return 0;
        }));
        ctx.packaging(PackagerSpec.replacingMainArtifact("probe-package")
                .produce(io -> io.label("offline=" + io.offline())));
    };

    @Test
    void an_online_job_tells_every_surface_so(@TempDir Path dir) throws Exception {
        assertThat(policySeenBy("run-step", "probe-step", spec(dir, "run-step", "probe-step", Boolean.FALSE)))
                .isEqualTo("offline=false");
        assertThat(policySeenBy("command", "probe-command", spec(dir, "command", "probe-command", Boolean.FALSE)))
                .isEqualTo("offline=false");
        assertThat(policySeenBy("package", "probe-package", spec(dir, "package", null, Boolean.FALSE)))
                .isEqualTo("offline=false");
    }

    @Test
    void an_offline_job_tells_every_surface_so(@TempDir Path dir) throws Exception {
        assertThat(policySeenBy("run-step", "probe-step", spec(dir, "run-step", "probe-step", Boolean.TRUE)))
                .isEqualTo("offline=true");
        assertThat(policySeenBy("command", "probe-command", spec(dir, "command", "probe-command", Boolean.TRUE)))
                .isEqualTo("offline=true");
        assertThat(policySeenBy("package", "probe-package", spec(dir, "package", null, Boolean.TRUE)))
                .isEqualTo("offline=true");
    }

    @Test
    void a_spec_that_states_no_policy_is_offline(@TempDir Path dir) throws Exception {
        assertThat(policySeenBy("run-step", "probe-step", spec(dir, "run-step", "probe-step", null)))
                .isEqualTo("offline=true");
        assertThat(policySeenBy("command", "probe-command", spec(dir, "command", "probe-command", null)))
                .isEqualTo("offline=true");
        assertThat(policySeenBy("package", "probe-package", spec(dir, "package", null, null)))
                .isEqualTo("offline=true");
    }

    /** Run the harness against {@code specFile} and return the single {@code offline=…} it reported. */
    private static String policySeenBy(String op, String name, Path specFile) throws Exception {
        Capture out = capture();
        int exit = BuildPluginHarness.run(FIXTURE, List.of(specFile.toString()), out.writer());
        assertThat(exit).describedAs("%s %s exit", op, name).isZero();
        return out.lines().stream()
                .map(WorkerOfflinePolicyTest::payload)
                .filter(s -> s.startsWith("offline="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no policy reported for " + op + ": " + out.lines()));
    }

    /** The {@code text}/{@code line} payload of a label or command-out reply. */
    private static String payload(String replyLine) {
        int at = replyLine.indexOf("offline=");
        if (at < 0) return "";
        int end = replyLine.indexOf('"', at);
        return end < 0 ? "" : replyLine.substring(at, end);
    }

    private static Path spec(Path dir, String op, String name, Boolean offline) throws Exception {
        Path spec = dir.resolve(op + "-" + offline + ".spec");
        List<String> lines = new ArrayList<>(List.of(
                "{\"t\":\"op\",\"op\":\"" + op + "\""
                        + (name == null ? "" : ",\"name\":\"" + name + "\"") + ",\"plugin\":\"fx\"}",
                "{\"t\":\"project\",\"group\":\"g\",\"name\":\"n\",\"version\":\"1\",\"javaRelease\":25,"
                        + "\"nativeDeclared\":false,\"kotlin\":false}"));
        if (offline != null) lines.add("{\"t\":\"offline\",\"value\":" + offline + "}");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    private record Capture(ByteArrayOutputStream buffer, ProtocolWriter writer) {
        List<String> lines() {
            return buffer.toString(StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static Capture capture() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        return new Capture(buffer, new ProtocolWriter(stream, "##FX:"));
    }
}
