// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.ProjectFacts;
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
 * A step body reads the plugin's secrets off its spec, beside a config that does not hold them, and
 * a secret the spec does not carry is empty.
 */
class StepSecretTest {

    private static final BuildPlugin FIXTURE = ctx -> ctx.task(TaskSpec.named("live")
            .inputs(In.config())
            .outputs("out")
            .run(exec -> exec.label("password="
                    + exec.secret("db-password").orElse("none")
                    + " user=" + exec.secret("db-user").orElse("none")
                    + " config=" + exec.config().values().keySet())));

    @Test
    void a_step_reads_its_secret_off_the_spec_and_not_off_its_config(@TempDir Path dir) throws Exception {
        Path scratch = Files.createDirectories(dir.resolve("scratch"));
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "live", "fx")
                .configValues(Map.of("url", "jdbc:x"))
                .project(new ProjectFacts("g", "n", "1", 25, null, false, false, Map.of()))
                .layout(dir.resolve("classes"), dir, scratch)
                .secret("db-password", "s3cret")
                .writeTempSpec();
        var buffer = new ByteArrayOutputStream();
        var out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:");

        int exit;
        try {
            exit = BuildPluginHarness.run(FIXTURE, List.of(spec.toString()), out);
        } finally {
            Files.deleteIfExists(spec);
        }

        assertThat(exit).isZero();
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("password=s3cret user=none config=[url]");
    }
}
