// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1696: the AOT properties file is read by the step body, so it has to be in the step's action
 * key. Asserted on the describe protocol — that JSONL is what the engine fingerprints, so a test
 * against the plugin's own field would prove nothing about the cache.
 */
class MicronautAotInputsTest {

    @Test
    void the_conventional_properties_file_is_a_declared_input(@TempDir Path dir) throws Exception {
        assertThat(inputsOf(describe(dir, null))).contains("project:aot.properties");
    }

    /** Declared even when absent: {@code missing:} is its own fingerprint, so creating it re-runs. */
    @Test
    void the_conventional_file_is_declared_even_when_it_does_not_exist(@TempDir Path dir) throws Exception {
        assertThat(Files.exists(dir.resolve("aot.properties"))).isFalse();
        assertThat(inputsOf(describe(dir, null))).contains("project:aot.properties");
    }

    @Test
    void an_explicit_aot_config_path_is_the_declared_input(@TempDir Path dir) throws Exception {
        String line = describe(dir, "cfg/native-aot.properties");
        assertThat(inputsOf(line)).contains("project:cfg/native-aot.properties");
        assertThat(inputsOf(line)).doesNotContain("project:aot.properties");
    }

    @Test
    void the_declared_input_is_the_file_the_body_opens(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("cfg"));
        Files.writeString(dir.resolve("cfg/native-aot.properties"), "scan.reactive.types.enabled=false\n");
        String spec = MicronautPlugin.configSpec(
                new cc.jumpkick.plugin.PluginConfig("micronaut", Map.of("aot-config", "cfg/native-aot.properties")));
        assertThat(dir.resolve(spec)).isEqualTo(MicronautPlugin.userConfigFile(dir, "cfg/native-aot.properties"));
        assertThat(inputsOf(describe(dir, "cfg/native-aot.properties"))).contains("project:" + spec);
    }

    /** The `micronaut-aot` task line from a describe run with aot forced on. */
    private static String describe(Path dir, String aotConfig) throws Exception {
        Path spec = dir.resolve("describe.spec");
        List<String> lines = new ArrayList<>(List.of(
                "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-micronaut\"}",
                "{\"t\":\"config\",\"key\":\"version\",\"kind\":\"string\",\"value\":\"5\"}",
                "{\"t\":\"config\",\"key\":\"aot\",\"kind\":\"bool\",\"value\":true}",
                "{\"t\":\"project\",\"group\":\"com.example\",\"name\":\"svc\",\"version\":\"1\","
                        + "\"javaRelease\":25,\"nativeDeclared\":false,\"kotlin\":false}"));
        if (aotConfig != null) {
            lines.add(
                    2, "{\"t\":\"config\",\"key\":\"aot-config\",\"kind\":\"string\",\"value\":\"" + aotConfig + "\"}");
        }
        Files.write(spec, lines);

        var buffer = new ByteArrayOutputStream();
        var writer = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKMN:");
        int exit = new MicronautPlugin().run(List.of(spec.toString()), writer);
        assertThat(exit).isZero();
        return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n")).stream()
                .filter(l -> l.contains("\"t\":\"task\"") && l.contains("\"name\":\"micronaut-aot\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no micronaut-aot task line in:\n" + buffer));
    }

    /** The `inputs` array of a describe task line, as wire names. */
    private static List<String> inputsOf(String taskLine) {
        int at = taskLine.indexOf("\"inputs\":[");
        assertThat(at).as("inputs in " + taskLine).isNotNegative();
        int open = at + "\"inputs\":[".length();
        String body = taskLine.substring(open, taskLine.indexOf(']', open));
        return List.of(body.split(",")).stream()
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
