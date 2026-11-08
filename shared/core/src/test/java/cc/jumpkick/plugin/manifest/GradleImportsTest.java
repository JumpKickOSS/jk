// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradleImportsTest {

    private static final String HEAD = """
            [plugin]
            id        = "acme-kafka"
            table     = "acme-kafka"
            version   = "1.0.0"
            jk-compat = ">=0.10"

            """;

    @Test
    void a_rule_names_the_table_the_object_its_properties_and_the_files_it_reads() {
        PluginDescriptor descriptor = PluginDescriptors.parse(HEAD + """
                [[import.gradle-plugin]]
                id         = "com.acme.kafka-helper"
                table      = "schema-registry"
                task       = "sharedSchemasLoader"
                properties = { sourceSchemaRegistry = "url" }
                files      = { "src/main/resources/kafka/shared-schema.yaml#schemas" = "subjects" }
                """, "acme-kafka/jk-plugin.toml", false);

        assertThat(descriptor.gradleImports())
                .containsExactly(new PluginDescriptor.GradleImport(
                        "com.acme.kafka-helper",
                        null,
                        null,
                        "schema-registry",
                        null,
                        "sharedSchemasLoader",
                        Map.of("sourceSchemaRegistry", "url"),
                        Map.of("src/main/resources/kafka/shared-schema.yaml#schemas", "subjects")));
    }

    @Test
    void properties_need_the_object_they_are_read_from() {
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[import.gradle-plugin]]
                        id         = "com.acme.kafka-helper"
                        properties = { sourceSchemaRegistry = "url" }
                        """, "acme-kafka/jk-plugin.toml", false))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("properties need an extension or task");
    }
}
