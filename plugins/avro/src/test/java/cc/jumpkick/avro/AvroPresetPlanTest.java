// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.avro;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [avro]} table as the generator entry it expands to. */
class AvroPresetPlanTest {

    private static final Path SHIM = Path.of("/shelf/jk-avro.jar");

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new AvroPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-avro");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKAVRO:");
    }

    /** An empty table: every schema, protocol and IDL file under src/main/avro, the compiler's defaults. */
    @Test
    void the_defaults_generate_from_the_maven_layout() {
        GeneratorEntry entry = AvroPreset.entry(new PluginConfig("avro", Map.of()), List.of(SHIM));

        assertThat(entry.name()).isEqualTo("avro");
        assertThat(entry.stepName()).isEqualTo("generate-avro");
        assertThat(entry.toolArtifact()).isEqualTo("avro-compiler");
        assertThat(entry.toolCoordinate()).isEqualTo("org.apache.avro:avro-compiler");
        assertThat(entry.main()).isEqualTo("cc.jumpkick.avro.AvroMain");
        assertThat(entry.inputs())
                .containsExactly("src/main/avro/**/*.avsc", "src/main/avro/**/*.avpr", "src/main/avro/**/*.avdl");
        assertThat(entry.args()).containsExactly("--out", "${out}", "${inputs}");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.out()).isEqualTo("generated/avro");
        assertThat(entry.classpath()).containsExactly(SHIM);
    }

    @Test
    void every_key_reaches_the_main() {
        GeneratorEntry entry = AvroPreset.entry(
                new PluginConfig(
                        "avro",
                        Map.of(
                                "src",
                                "schemas",
                                "string-type",
                                "CharSequence",
                                "field-visibility",
                                "PUBLIC",
                                "setters",
                                false,
                                "optional-getters",
                                true,
                                "decimal-logical-type",
                                true,
                                "encoding",
                                "ISO-8859-1")),
                List.of(SHIM));

        assertThat(entry.inputs()).containsExactly("schemas/**/*.avsc", "schemas/**/*.avpr", "schemas/**/*.avdl");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--string-type",
                        "CharSequence",
                        "--field-visibility",
                        "PUBLIC",
                        "--no-setters",
                        "--optional-getters",
                        "--decimal-logical-type",
                        "--encoding",
                        "ISO-8859-1",
                        "${inputs}");
    }

    @Test
    void the_worker_names_its_own_code_source() {
        assertThat(AvroPreset.ownJar()).singleElement().satisfies(p -> assertThat(p)
                .exists());
    }
}
