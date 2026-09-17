// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * What the generator plugin declares to the engine, on the describe protocol: one generate-stage
 * task per entry, the inputs' glob bases and the config as its key, the output dir contributed.
 */
class GeneratorsPlanTest {

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new Generators().manifest();
        assertThat(manifest.id()).isEqualTo("jk-generator");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKGEN:");
    }

    @Test
    void one_task_per_entry_keyed_on_its_glob_bases_and_config(@TempDir Path dir) throws Exception {
        List<String> lines = describe(
                dir,
                List.of(
                        entry("api", "tool", "string", "\"org.acme:gen:1.0\""),
                        entry("api", "inputs", "list", "[\"api/openapi.yaml\",\"api/*.json\"]"),
                        entry("grammar", "tool", "string", "\"org.antlr:antlr4:4.13.2\""),
                        entry("grammar", "inputs", "list", "[\"src/main/antlr/**/*.g4\"]"),
                        entry("grammar", "contributes", "string", "\"resources\""),
                        entry("grammar", "out", "string", "\"parsers\"")));

        String api = task(lines, "generate-api");
        assertThat(arrayOf(api, "inputs")).containsExactly("project:api/openapi.yaml", "project:api", "config");
        assertThat(arrayOf(api, "outputs")).containsExactly("generated/api");
        assertThat(arrayOf(api, "contributesSources")).containsExactly("generated/api");
        assertThat(arrayOf(api, "contributesResources")).isEmpty();
        assertThat(api).contains("\"stage\":\"generate\"");

        String grammar = task(lines, "generate-grammar");
        assertThat(arrayOf(grammar, "inputs")).containsExactly("project:src/main/antlr", "config");
        assertThat(arrayOf(grammar, "outputs")).containsExactly("parsers");
        assertThat(arrayOf(grammar, "contributesResources")).containsExactly("parsers");
        assertThat(arrayOf(grammar, "contributesSources")).isEmpty();
        assertThat(lines.stream().filter(l -> l.contains("\"t\":\"task\"")).count())
                .isEqualTo(2);
    }

    /** zipkin-server's shape: protos unpacked from a jar, generated for the test compile. */
    @Test
    void an_unpack_entry_needs_no_inputs_and_test_sources_reach_the_test_compile(@TempDir Path dir) throws Exception {
        List<String> lines = describe(
                dir,
                List.of(
                        entry("wire", "tool", "string", "\"com.squareup.wire:wire-compiler:5.5.1\""),
                        entry("wire", "unpack", "string", "\"io.zipkin.proto3:zipkin-proto3:1.0.0\""),
                        entry("wire", "contributes", "string", "\"test-sources\"")));

        String wire = task(lines, "generate-wire");
        assertThat(arrayOf(wire, "inputs")).containsExactly("config");
        assertThat(arrayOf(wire, "contributesTestSources")).containsExactly("generated/wire");
        assertThat(arrayOf(wire, "contributesSources")).isEmpty();
        assertThat(wire).contains("\"stage\":\"generate\"");
    }

    @Test
    void an_entry_with_neither_inputs_nor_unpack_is_refused() {
        assertThatThrownBy(() -> GeneratorEntry.fromConfig("api", Map.of("tool", "g:a:1")))
                .hasMessageContaining("[generate.api] declares no inputs")
                .hasMessageContaining("unpack");
    }

    @Test
    void an_unknown_contribution_is_refused_naming_the_three() {
        assertThatThrownBy(() -> GeneratorEntry.fromConfig(
                        "api", Map.of("tool", "g:a:1", "inputs", List.of("x"), "contributes", "classes")))
                .hasMessageContaining("sources, test-sources or resources");
    }

    private static String entry(String name, String field, String kind, String json) {
        String valueKey = kind.equals("list") ? "values" : "value";
        return "{\"t\":\"config\",\"key\":\"*\",\"entry\":\"" + name + "\",\"field\":\"" + field + "\",\"kind\":\""
                + kind + "\",\"" + valueKey + "\":" + json + "}";
    }

    private static List<String> describe(Path dir, List<String> config) throws Exception {
        Path spec = dir.resolve("describe.spec");
        List<String> lines = new ArrayList<>();
        lines.add("{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-generator\"}");
        lines.addAll(config);
        lines.add("{\"t\":\"project\",\"group\":\"com.example\",\"name\":\"svc\",\"version\":\"1\","
                + "\"javaRelease\":25,\"nativeDeclared\":false,\"kotlin\":false}");
        Files.write(spec, lines);
        var buffer = new ByteArrayOutputStream();
        var writer = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKGEN:");
        int exit = new Generators().run(List.of(spec.toString()), writer);
        assertThat(exit).isZero();
        return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
    }

    private static String task(List<String> lines, String name) {
        return lines.stream()
                .filter(l -> l.contains("\"t\":\"task\"") && l.contains("\"name\":\"" + name + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no task " + name + " in " + lines));
    }

    /** A JSON string array field of a describe line, as its element strings. */
    static List<String> arrayOf(String line, String field) {
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
