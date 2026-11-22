// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.ProtocolWriter;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command body reads its plugin's secret keys from the spec's {@code secret} lines, as a step
 * and a packager do, and finds none of them in its config.
 */
class CommandExecSecretTest {

    private static final BuildPlugin FIXTURE = ctx -> ctx.command(PluginCommandSpec.named("probe")
            .description("report whether the secret arrived")
            .run(exec -> {
                exec.out("token " + (exec.secret("token").orElse("").equals("sesame") ? "read" : "missing"));
                exec.out("other " + (exec.secret("other").isPresent() ? "present" : "absent"));
                exec.out("in config " + exec.config().values().containsKey("token"));
                return 0;
            }));

    @Test
    void a_command_reads_the_secret_its_spec_carries(@TempDir Path dir) throws Exception {
        Path spec = Files.write(
                dir.resolve("probe.spec"),
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "probe", "fx")
                        .configValues(Map.of("user", "app"))
                        .javaHome(dir)
                        .secret("token", "sesame")
                        .commandArgs(List.of())
                        .lines(),
                StandardCharsets.UTF_8);
        var buffer = new ByteArrayOutputStream();
        var out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:");

        int exit = BuildPluginHarness.run(FIXTURE, List.of(spec.toString()), out);

        assertThat(exit).isZero();
        String replies = buffer.toString(StandardCharsets.UTF_8);
        assertThat(replies)
                .contains("token read")
                .contains("other absent")
                .contains("in config false")
                .doesNotContain("sesame");
    }
}
