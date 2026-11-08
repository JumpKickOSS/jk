// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonschema;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code [json-schema]} table as the generator entry it expands to, and the main over jsonschema2pojo. */
class JsonSchemaPresetTest {

    private static final Path SHIM = Path.of("/shelf/jk-json-schema.jar");

    @Test
    void the_defaults_generate_jackson_classes_from_the_gradle_plugin_s_layout() {
        GeneratorEntry entry = JsonSchemaPreset.entry(new PluginConfig("json-schema", Map.of()), List.of(SHIM));

        assertThat(entry.stepName()).isEqualTo("generate-json-schema");
        assertThat(entry.toolCoordinate()).isEqualTo("org.jsonschema2pojo:jsonschema2pojo-cli");
        assertThat(entry.main()).isEqualTo("cc.jumpkick.jsonschema.JsonSchemaMain");
        assertThat(entry.inputs()).containsExactly("src/main/resources/json/**/*.json");
        assertThat(entry.args())
                .containsExactly(
                        "--root",
                        "${module.dir}/src/main/resources/json",
                        "-t",
                        "${out}",
                        "-T",
                        "JSONSCHEMA",
                        "-a",
                        "JACKSON",
                        "-D",
                        "--",
                        "${inputs}");
        assertThat(entry.classpath()).containsExactly(SHIM);
    }

    @Test
    void every_key_reaches_jsonschema2pojo() {
        GeneratorEntry entry = JsonSchemaPreset.entry(
                new PluginConfig(
                        "json-schema",
                        Map.ofEntries(
                                Map.entry("src", "schemas"),
                                Map.entry("package", "com.acme.events"),
                                Map.entry("source-type", "yamlschema"),
                                Map.entry("annotation-style", "jackson3"),
                                Map.entry("builders", true),
                                Map.entry("jsr303", true),
                                Map.entry("jakarta-validation", true),
                                Map.entry("additional-properties", false),
                                Map.entry("date-time-type", "java.time.OffsetDateTime"))),
                List.of(SHIM));

        assertThat(entry.inputs()).containsExactly("schemas/**/*.yaml", "schemas/**/*.yml");
        assertThat(entry.args())
                .containsSubsequence("-T", "YAMLSCHEMA", "-a", "JACKSON3")
                .containsSubsequence("-dtt", "java.time.OffsetDateTime", "-p", "com.acme.events")
                .contains("-b", "-303", "--useJakartaValidation")
                .doesNotContain("-D");
    }

    /** Files under a root are generated from the root; a file under none is its own source. */
    @Test
    void the_main_hands_jsonschema2pojo_each_root_once_and_loose_files_by_themselves() {
        String[] cli = JsonSchemaMain.cli(new String[] {
            "--root",
            "/m/src/main/resources/json",
            "-t",
            "/out",
            "-a",
            "JACKSON2",
            "--",
            "/m/src/main/resources/json/order.json",
            "/m/src/main/resources/json/common/money.json",
            "/store/s/json/x.json"
        });

        assertThat(cli)
                .containsExactly(
                        "-t", "/out", "-a", "JACKSON2", "-s", "/m/src/main/resources/json,/store/s/json/x.json");
    }

    /** A schema naming another file by $ref generates both, the referenced one in its subpackage. */
    @Test
    void jsonschema2pojo_generates_a_schema_and_the_file_it_refs(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("json/common"));
        Files.writeString(root.resolve("money.json"), """
                {"type": "object", "properties": {"cents": {"type": "integer"}}}
                """);
        Files.writeString(tmp.resolve("json/order.json"), """
                {"type": "object", "properties": {"total": {"$ref": "common/money.json"}}}
                """);
        Path out = tmp.resolve("out");

        JsonSchemaMain.main(new String[] {
            "--root",
            tmp.resolve("json").toString(),
            "-t",
            out.toString(),
            "-p",
            "com.acme",
            "-D",
            "--",
            tmp.resolve("json/order.json").toString(),
            root.resolve("money.json").toString()
        });

        assertThat(out.resolve("com/acme/Order.java")).exists();
        assertThat(out.resolve("com/acme/common/Money.java")).exists();
        assertThat(Files.readString(out.resolve("com/acme/Order.java")))
                .contains("import com.acme.common.Money;", "private Money total;");
    }
}
