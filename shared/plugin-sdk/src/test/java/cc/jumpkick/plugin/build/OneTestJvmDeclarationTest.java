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
 * A step that says the module's tests run in one JVM ({@code oneTestJvm}) says so on its describe
 * line, and one that says nothing describes {@code false}: the engine reads the flag off the
 * declaration, so a framework plugin pins the suite without the manifest naming it.
 */
class OneTestJvmDeclarationTest {

    private static final BuildPlugin FIXTURE = ctx -> {
        ctx.task(TaskSpec.named("model")
                .inputs(In.classes())
                .outputs("tm")
                .contributesTestJvmArgs("tm/jvm.args")
                .oneTestJvm()
                .run(exec -> {}));
        ctx.task(TaskSpec.named("gen").inputs(In.classes()).outputs("out").run(exec -> {}));
    };

    @Test
    void describe_carries_the_one_test_jvm_flag_per_step(@TempDir Path dir) throws Exception {
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
                .anyMatch(l -> l.contains("\"name\":\"model\"") && l.contains("\"oneTestJvm\":true"))
                .anyMatch(l -> l.contains("\"name\":\"gen\"") && l.contains("\"oneTestJvm\":false"));
        assertThat(TaskSpec.named("x").runsTestsInOneJvm()).isFalse();
    }
}
