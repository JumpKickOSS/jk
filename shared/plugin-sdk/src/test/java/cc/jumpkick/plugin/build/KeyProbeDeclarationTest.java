// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step with a key probe names it on its describe line — main, closures and arguments — and a step
 * without one names none, so the engine measures only what a plugin asked it to.
 */
class KeyProbeDeclarationTest {

    private static final BuildPlugin FIXTURE = ctx -> {
        ctx.task(TaskSpec.named("live")
                .inputs(In.config())
                .outputs("out")
                .keyProbe(new KeyProbe("com.acme.Digest", List.of("driver"), List.of("--url", "jdbc:x")))
                .run(exec -> {}));
        ctx.task(TaskSpec.named("files").inputs(In.config()).outputs("out").run(exec -> {}));
    };

    @Test
    void describe_carries_the_probe_of_the_step_that_declares_one(@TempDir Path dir) throws Exception {
        Path spec = dir.resolve("describe.spec");
        Files.write(
                spec,
                List.of(
                        "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"fx\"}",
                        "{\"t\":\"project\",\"group\":\"g\",\"name\":\"n\",\"version\":\"1\",\"javaRelease\":25,"
                                + "\"nativeDeclared\":false,\"kotlin\":false}"));
        var buffer = new ByteArrayOutputStream();
        var out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:");

        int exit = BuildPluginHarness.run(FIXTURE, List.of(spec.toString()), out);

        assertThat(exit).isZero();
        List<String> lines = List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
        assertThat(lines)
                .anyMatch(l -> l.contains("\"name\":\"live\"")
                        && l.contains("\"probeMain\":\"com.acme.Digest\"")
                        && l.contains("\"probeTools\":[\"driver\"]")
                        && l.contains("\"probeArgs\":[\"--url\",\"jdbc:x\"]"))
                .anyMatch(l -> l.contains("\"name\":\"files\"") && !l.contains("probeMain"));
    }
}
