// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRulesTest {

    private static final String KAFKA_HELPER = "com.acme.kafka-helper";

    /**
     * A company's private Gradle plugin downloads registry subjects its module lists in a YAML file
     * and reads the registry URL off its task; a jk plugin's rule maps both into [schema-registry].
     */
    private static final PluginRules.Rule KAFKA_HELPER_RULE = new PluginRules.Rule(
            "acme-kafka",
            new PluginDescriptor.GradleImport(
                    KAFKA_HELPER,
                    null,
                    null,
                    "schema-registry",
                    null,
                    "sharedSchemasLoader",
                    Map.of("sourceSchemaRegistry", "url"),
                    Map.of("src/main/resources/kafka/shared-schema.yaml#schemas", "subjects"),
                    Map.of()));

    @Test
    void a_private_plugin_s_task_and_the_file_it_reads_become_schema_registry_keys(@TempDir Path module)
            throws Exception {
        Path yaml = module.resolve("src/main/resources/kafka/shared-schema.yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, """
                # the subjects the loader downloads
                schemas:
                  mirror.public.det.flag.compactedevent.1-value: 18
                  orders-value: 3
                """);
        ImportReport.Builder report = ImportReport.builder();

        List<PluginConfig> tables = PluginRules.map(
                Set.of(KAFKA_HELPER),
                Map.of(),
                Map.of(KAFKA_HELPER, Map.of("sourceSchemaRegistry", "https://registry.example.com")),
                module,
                Map.of(KAFKA_HELPER, KAFKA_HELPER_RULE),
                report);

        assertThat(tables)
                .containsExactly(new PluginConfig(
                        "schema-registry",
                        Map.of(
                                "url",
                                "https://registry.example.com",
                                "subjects",
                                Map.of("mirror.public.det.flag.compactedevent.1-value", "18", "orders-value", "3"))));
        assertThat(report.build().issues()).isEmpty();
    }

    @Test
    void extension_properties_take_the_key_s_type_and_a_default_is_left_out(@TempDir Path module) throws Exception {
        Files.writeString(module.resolve("avro.properties"), "encoding=UTF-16\n");
        PluginRules.Rule rule = new PluginRules.Rule(
                "avro",
                new PluginDescriptor.GradleImport(
                        "com.acme.avro",
                        null,
                        null,
                        null,
                        "avro",
                        null,
                        Map.of(
                                "stringType",
                                "string-type",
                                "createSetters",
                                "setters",
                                "decimal",
                                "decimal-logical-type"),
                        Map.of("avro.properties#encoding", "encoding"),
                        Map.of()));

        List<PluginConfig> tables = PluginRules.map(
                Set.of("com.acme.avro"),
                Map.of(),
                Map.of("com.acme.avro", Map.of("stringType", "String", "createSetters", true, "decimal", "true")),
                module,
                Map.of("com.acme.avro", rule),
                ImportReport.builder());

        assertThat(tables)
                .containsExactly(new PluginConfig("avro", Map.of("decimal-logical-type", true, "encoding", "UTF-16")));
    }

    @Test
    void a_plugin_the_project_does_not_apply_writes_nothing(@TempDir Path module) {
        assertThat(PluginRules.map(
                        Set.of("java"),
                        Map.of(),
                        Map.of(),
                        module,
                        Map.of(KAFKA_HELPER, KAFKA_HELPER_RULE),
                        ImportReport.builder()))
                .isEmpty();
    }

    @Test
    void the_init_script_is_told_the_properties_of_each_rule_that_reads_any() {
        assertThat(PluginRules.readsJson(Map.of(KAFKA_HELPER, KAFKA_HELPER_RULE)))
                .isEqualTo("[{\"id\":\"" + KAFKA_HELPER
                        + "\",\"task\":\"sharedSchemasLoader\",\"properties\":[\"sourceSchemaRegistry\"]}]");
    }

    @Test
    void credential_properties_become_the_table_s_repository_and_name_their_variables(@TempDir Path module) {
        PluginRules.Rule rule = new PluginRules.Rule(
                "import.toml",
                new PluginDescriptor.GradleImport(
                        KAFKA_HELPER,
                        null,
                        null,
                        "schema-registry",
                        null,
                        "sharedSchemasLoader",
                        Map.of("sourceSchemaRegistry", "url"),
                        Map.of(),
                        Map.of("username", "apiKey", "password", "apiSecret")));
        List<PluginRules.Credential> found = new ArrayList<>();

        List<PluginConfig> tables = PluginRules.map(
                Set.of(KAFKA_HELPER),
                Map.of(),
                Map.of(
                        KAFKA_HELPER,
                        Map.of(
                                "sourceSchemaRegistry", "https://registry.example.com",
                                "apiKey", Map.of("env", "REGISTRY_KEY"),
                                "apiSecret", Map.of())),
                module,
                Map.of(KAFKA_HELPER, rule),
                ImportReport.builder(),
                found::add);

        assertThat(tables.getFirst().values())
                .containsEntry("url", "https://registry.example.com")
                .containsEntry("repository", "kafka-helper");
        assertThat(found)
                .containsExactly(new PluginRules.Credential(
                        "kafka-helper", "https://registry.example.com", "REGISTRY_KEY", null));
        assertThat(PluginRules.readsJson(Map.of(KAFKA_HELPER, rule))).contains("\"secrets\":[");
    }
}
