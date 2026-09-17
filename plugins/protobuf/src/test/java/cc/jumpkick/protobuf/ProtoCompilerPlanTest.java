// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the protobuf plugin declares to the engine, asserted on the describe protocol — that JSONL
 * is what the engine fingerprints and wires into the plan, so a test against the plugin's own
 * fields would prove nothing about the cache key or the source-set contribution.
 */
class ProtoCompilerPlanTest {

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new ProtoCompiler().manifest();
        assertThat(manifest.id()).isEqualTo("jk-protobuf");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKPB:");
    }

    /**
     * One step; its inputs are the proto dir, its config and the runtime and compile classpaths
     * (whose jars may carry importable protos); one output that is also the source contribution.
     */
    @Test
    void declares_one_protoc_step_over_the_default_proto_dir(@TempDir Path dir) throws Exception {
        String line = protocTask(dir, null).orElseThrow(() -> new AssertionError("no protoc task line"));
        assertThat(line).contains("\"name\":\"protoc\"");
        assertThat(arrayOf(line, "requires")).isEmpty();
        assertThat(arrayOf(line, "inputs"))
                .containsExactly("project:proto", "sibling:src", "config", "runtime-classpath", "compile-classpath");
        assertThat(arrayOf(line, "outputs")).containsExactly("gen");
        assertThat(arrayOf(line, "contributesSources")).containsExactly("gen");
        assertThat(arrayOf(line, "contributesClasses")).isEmpty();
        assertThat(arrayOf(line, "contributesResources")).isEmpty();
        assertThat(arrayOf(line, "contributesTestClasspath")).isEmpty();
        assertThat(line).contains("\"transformsClasses\":\"\"");
        // No stage of its own: a source contribution runs before the language compile by the
        // engine's ordering, not by a stage the plugin picks.
        assertThat(line).contains("\"stage\":\"\"");
    }

    /** {@code [protobuf] src} moves the declared input with it — the engine fingerprints that dir, not `proto/`. */
    @Test
    void a_configured_src_dir_is_the_declared_input(@TempDir Path dir) throws Exception {
        String line = protocTask(dir, "src/main/proto").orElseThrow(() -> new AssertionError("no protoc task line"));
        assertThat(arrayOf(line, "inputs"))
                .containsExactly(
                        "project:src/main/proto", "sibling:src", "config", "runtime-classpath", "compile-classpath");
    }

    /** A list of proto roots declares one input per root, each fingerprinted by the engine. */
    @Test
    void a_list_of_src_dirs_declares_an_input_per_root(@TempDir Path dir) throws Exception {
        String line = protocTask(
                        dir,
                        "{\"t\":\"config\",\"key\":\"src\",\"kind\":\"list\",\"values\":[\"proto\",\"src/main/proto\"]}")
                .orElseThrow(() -> new AssertionError("no protoc task line"));
        assertThat(arrayOf(line, "inputs"))
                .containsExactly(
                        "project:proto",
                        "project:src/main/proto",
                        "sibling:src",
                        "config",
                        "runtime-classpath",
                        "compile-classpath");
    }

    /** No packager and no commands: the plugin is a single codegen step. */
    @Test
    void contributes_no_packager_and_no_command(@TempDir Path dir) throws Exception {
        List<String> lines = describe(dir, null);
        assertThat(lines).noneMatch(l -> l.contains("\"t\":\"packager\""));
        assertThat(lines).noneMatch(l -> l.contains("\"t\":\"command\""));
        assertThat(lines.stream().filter(l -> l.contains("\"t\":\"task\"")).count())
                .isEqualTo(1);
    }

    private static Optional<String> protocTask(Path dir, @Nullable String src) throws Exception {
        return describe(dir, src).stream()
                .filter(l -> l.contains("\"t\":\"task\"") && l.contains("\"name\":\"protoc\""))
                .findFirst();
    }

    /** Run the plugin's describe op over a spec with the given `src` (absent when null; a whole config line when it starts with a brace). */
    private static List<String> describe(Path dir, @Nullable String src) throws Exception {
        Path spec = dir.resolve("describe.spec");
        List<String> lines = new ArrayList<>(List.of(
                "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-protobuf\"}",
                "{\"t\":\"project\",\"group\":\"com.example\",\"name\":\"svc\",\"version\":\"1\","
                        + "\"javaRelease\":25,\"nativeDeclared\":false,\"kotlin\":false}"));
        if (src != null) {
            lines.add(
                    1,
                    src.startsWith("{")
                            ? src
                            : "{\"t\":\"config\",\"key\":\"src\",\"kind\":\"string\",\"value\":\"" + src + "\"}");
        }
        Files.write(spec, lines);
        var buffer = new ByteArrayOutputStream();
        var writer = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPB:");
        int exit = new ProtoCompiler().run(List.of(spec.toString()), writer);
        assertThat(exit).isZero();
        return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
    }

    /** A JSON string array field of a describe line, as its element strings. */
    private static List<String> arrayOf(String line, String field) {
        String key = "\"" + field + "\":[";
        int at = line.indexOf(key);
        assertThat(at).as(field + " in " + line).isNotNegative();
        int open = at + key.length();
        String body = line.substring(open, line.indexOf(']', open));
        return List.of(body.split(",")).stream()
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
