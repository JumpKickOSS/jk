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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AOT properties file is read by the step body, so it has to be in the step's action
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

    /**
     * The auto-gate at {@code MicronautPlugin.build}: with no {@code aot} key, a declared
     * {@code [native]} turns AOT on by itself (mirroring spring-boot). Every other fixture in this
     * file forces {@code aot = true}, so this is the only case where {@code nativeDeclared}
     * actually decides.
     */
    @Test
    void a_declared_native_build_turns_aot_on_with_no_aot_key(@TempDir Path dir) throws Exception {
        assertThat(aotTaskLine(dir, null, true, null)).isPresent();
        assertThat(aotTaskLine(dir, null, false, null)).isEmpty();
    }

    /** An explicit {@code aot = false} outranks {@code [native]}: the key always wins. */
    @Test
    void an_explicit_false_suppresses_aot_even_for_a_native_build(@TempDir Path dir) throws Exception {
        assertThat(aotTaskLine(dir, false, true, null)).isEmpty();
    }

    /** The `micronaut-aot` task line from a describe run with aot forced on. */
    private static String describe(Path dir, String aotConfig) throws Exception {
        return aotTaskLine(dir, true, false, aotConfig)
                .orElseThrow(() -> new AssertionError("no micronaut-aot task line"));
    }

    /**
     * Run the plugin's describe op and return the {@code micronaut-aot} task line, if the plugin
     * contributed one. {@code aot} null means the key is absent — the only shape in which
     * {@code nativeDeclared} gets to decide.
     */
    private static Optional<String> aotTaskLine(Path dir, Boolean aot, boolean nativeDeclared, String aotConfig)
            throws Exception {
        Path spec = dir.resolve("describe.spec");
        List<String> lines = new ArrayList<>(List.of(
                "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-micronaut\"}",
                "{\"t\":\"config\",\"key\":\"version\",\"kind\":\"string\",\"value\":\"5\"}",
                "{\"t\":\"project\",\"group\":\"com.example\",\"name\":\"svc\",\"version\":\"1\","
                        + "\"javaRelease\":25,\"nativeDeclared\":" + nativeDeclared + ",\"kotlin\":false}"));
        if (aot != null) {
            lines.add(2, "{\"t\":\"config\",\"key\":\"aot\",\"kind\":\"bool\",\"value\":" + aot + "}");
        }
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
                .findFirst();
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
