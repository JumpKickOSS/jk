// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                        Map.of("src/main/resources/kafka/shared-schema.yaml#schemas", "subjects"),
                        Map.of()));
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

    @Test
    void a_build_s_import_toml_holds_rules_with_their_table_and_credential_properties(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("import.toml");
        Files.writeString(file, """
                [[import.gradle-plugin]]
                id          = "com.acme.kafka-helper"
                table       = "schema-registry"
                task        = "sharedSchemasLoader"
                properties  = { sourceSchemaRegistry = "url" }
                credentials = { username = "apiKey", password = "apiSecret" }
                """);

        assertThat(GradleImports.read(file)).singleElement().satisfies(rule -> assertThat(rule.credentials())
                .containsExactlyInAnyOrderEntriesOf(Map.of("username", "apiKey", "password", "apiSecret")));
        assertThat(GradleImports.read(dir.resolve("missing.toml"))).isEmpty();
    }

    @Test
    void a_rule_in_an_import_toml_must_name_its_table(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("import.toml");
        Files.writeString(file, """
                [[import.gradle-plugin]]
                id   = "com.acme.kafka-helper"
                task = "sharedSchemasLoader"
                properties = { sourceSchemaRegistry = "url" }
                """);

        assertThatThrownBy(() -> GradleImports.read(file))
                .hasMessageEndingWith("com.acme.kafka-helper: set the table it writes");
    }

    @Test
    void credentials_take_only_a_username_and_a_password() {
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[import.gradle-plugin]]
                        id          = "com.acme.kafka-helper"
                        task        = "sharedSchemasLoader"
                        credentials = { token = "apiToken" }
                        """, "acme-kafka/jk-plugin.toml", false))
                .hasMessageContaining("credentials take username, password");
    }
}
