// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Schema compilers and where their schemas come from: Avro, registry downloads, a build's own rules. */
class GradleSchemaImportTest {

    /**
     * The Avro plugin, as a Kafka service build uses it: one module reads src/main/avro, another
     * reads schemas a registry-download task writes under build/.
     */
    private static final String AVRO_MODULES = """
            {"gradle":"9.8.0","rootName":"shop","settingsRepositories":[],"projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.0","plugins":[],"pluginClasses":[],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
              {"path":":events","projectName":"events","dir":"events","group":"com.acme","version":"1.0",
               "plugins":["java","com.github.davidmc24.gradle.plugin.avro","com.github.davidmc24.gradle.plugin.avro-base"],
               "pluginClasses":["com.github.davidmc24.gradle.plugin.avro.AvroPlugin","com.github.davidmc24.gradle.plugin.avro.AvroBasePlugin"],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[],
               "sourceSets":[{"name":"main","java":["src/main/java","build/generated-main-avro-java"],"resources":["src/main/resources"],"kotlin":[]}],
               "avro":{"sources":["src/main/avro","build/generated-main-avro-avpr"],"buildDir":"build"},
               "ruled":{"com.github.davidmc24.gradle.plugin.avro":{"stringType":"String","fieldVisibility":"PRIVATE",
                       "createSetters":true,"createOptionalGetters":false,"enableDecimalLogicalType":true}},
               "resolved":["org.apache.avro:avro@1.11.5"]},
              {"path":":flags","projectName":"flags","dir":"flags","group":"com.acme","version":"1.0",
               "plugins":["java","com.github.davidmc24.gradle.plugin.avro"],
               "pluginClasses":["com.github.davidmc24.gradle.plugin.avro.AvroPlugin"],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[],
               "avro":{"sources":["build/generated/avro/shared"],"buildDir":"build"},
               "ruled":{"com.github.davidmc24.gradle.plugin.avro":{"stringType":"CharSequence",
                       "fieldVisibility":"PUBLIC","createSetters":false,"enableDecimalLogicalType":false}}}
            ]}
            """;

    @Test
    void the_avro_plugin_imports_as_the_avro_table_with_only_what_differs() {
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(AVRO_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(module(result, "events").pluginConfig("avro").orElseThrow().values())
                .as("src/main/avro and String are the table's defaults; the plugin's decimal default is not")
                .containsOnly(Map.entry("decimal-logical-type", true), Map.entry("version", "1.11.5"));
        assertThat(module(result, "flags").pluginConfig("avro").orElseThrow().values())
                .containsOnly(
                        Map.entry("string-type", "CharSequence"),
                        Map.entry("field-visibility", "PUBLIC"),
                        Map.entry("setters", false));
        assertThat(messages(result.report()))
                .contains(
                        "flags: Avro schemas are generated into build/generated/avro/shared; put them in src/main/avro")
                .noneMatch(m -> m.contains("davidmc24"))
                .noneMatch(m -> m.contains("generated-main-avro-java"))
                .noneMatch(m -> m.contains("avpr") || m.contains("imported src/main/avro only"));
    }

    @Test
    void schemas_under_build_name_the_task_and_plugin_that_write_them() {
        String model = AVRO_MODULES.replace(
                "\"avro\":{\"sources\":[\"build/generated/avro/shared\"],\"buildDir\":\"build\"},",
                "\"avro\":{\"sources\":[\"build/generated/avro/shared\"],\"buildDir\":\"build\"},"
                        + "\"producers\":{\"build/generated/avro/shared\":[{\"task\":\"sharedSchemasLoader\","
                        + "\"type\":\"com.acme.loader.SharedSchemasLoaderTask\",\"plugin\":\"com.acme.kafka-helper\"}]},");
        assertThat(model).isNotEqualTo(AVRO_MODULES);

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(messages(result.report()))
                .contains(
                        "flags: Avro schemas come from task sharedSchemasLoader (kafka-helper); see [schema-registry]")
                .noneMatch(m -> m.contains("put them in src/main/avro"));
    }

    /**
     * A private plugin a rule in the build's .jk/import.toml teaches the import: the task's registry
     * URL and the subjects file become [schema-registry], and the key and secret the task reads
     * from the environment a credential table naming those variables.
     */
    @Test
    void a_build_s_own_rule_imports_a_private_plugin_and_its_credential(@TempDir Path shop) throws Exception {
        Files.createDirectories(shop.resolve(".jk"));
        Files.writeString(shop.resolve(".jk/import.toml"), """
                [[import.gradle-plugin]]
                id          = "com.acme.kafka-helper"
                table       = "schema-registry"
                task        = "sharedSchemasLoader"
                properties  = { sourceSchemaRegistry = "url" }
                files       = { "src/main/resources/kafka/shared-schema.yaml#schemas" = "subjects" }
                credentials = { username = "apiKey", password = "apiSecret" }
                """);
        Path yaml = shop.resolve("flags/src/main/resources/kafka/shared-schema.yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, "schemas:\n  flags-value: 18\n");
        String model = AVRO_MODULES
                .replace(
                        "\"plugins\":[\"java\",\"com.github.davidmc24.gradle.plugin.avro\"],",
                        "\"plugins\":[\"java\",\"com.github.davidmc24.gradle.plugin.avro\",\"com.acme.kafka-helper\"],")
                .replace(
                        "\"fieldVisibility\":\"PUBLIC\",",
                        "\"fieldVisibility\":\"PUBLIC\",\"x\":1},\"com.acme.kafka-helper\":{"
                                + "\"sourceSchemaRegistry\":\"https://registry.example.com\","
                                + "\"apiKey\":{\"env\":\"REGISTRY_KEY\"},\"apiSecret\":{\"env\":\"REGISTRY_SECRET\"},");
        assertThat(model).contains("REGISTRY_SECRET").contains("\"com.acme.kafka-helper\"]");

        GradleBuildImport.Result result = GradleModelImporter.importModel(model, shop, RefreshVersions.NONE);

        assertThat(module(result, "flags")
                        .pluginConfig("schema-registry")
                        .orElseThrow()
                        .values())
                .containsOnly(
                        Map.entry("url", "https://registry.example.com"),
                        Map.entry("subjects", Map.of("flags-value", "18")),
                        Map.entry("repository", "kafka-helper"));
        assertThat(result.machineCredentials()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("kafka-helper");
            assertThat(c.credential())
                    .containsExactly(
                            Map.entry("username", "${env:REGISTRY_KEY}"),
                            Map.entry("password", "${env:REGISTRY_SECRET}"));
        });
        assertThat(messages(result.report()))
                .contains(
                        "kafka-helper: credential added to ~/.jk/config.toml",
                        "on CI, set JK_REPO_<ID>_* for kafka-helper")
                .noneMatch(m -> m.contains("Avro schemas"))
                .noneMatch(m -> m.contains("kafka-helper: not imported"));
    }

    @Test
    void the_confluent_registry_plugin_s_downloads_import_as_schema_registry_with_its_credential() {
        String model = """
                {"gradle":"9.8.0","rootName":"orders","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"orders","dir":"","group":"com.acme","version":"1.0",
                   "plugins":["java","com.github.davidmc24.gradle.plugin.avro","com.github.imflog.kafka-schema-registry-gradle-plugin"],
                   "pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[],
                   "avro":{"sources":["src/main/avro/registry"],"buildDir":"build"},
                   "schemaRegistry":{"url":"https://registry.example.com","auth":true,
                     "usernameEnv":"SR_KEY","passwordEnv":"SR_SECRET","registers":true,"subjects":[
                       {"subject":"orders-value","version":3,"regex":false,"output":"src/main/avro/registry"},
                       {"subject":"money","regex":false,"output":"src/main/avro/registry"},
                       {"subject":"payments-.*","regex":true,"output":"src/main/avro/registry"}]}}
                ]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/orders"), RefreshVersions.NONE);

        assertThat(result.root().pluginConfig("schema-registry").orElseThrow().values())
                .containsOnly(
                        Map.entry("url", "https://registry.example.com"),
                        Map.entry("subjects", Map.of("orders-value", "3", "money", "latest")),
                        Map.entry("repository", "schema-registry"));
        assertThat(result.root().pluginConfig("avro").orElseThrow().values())
                .as("the download directory is the store's to fill, not a source root")
                .doesNotContainKey("src");
        assertThat(result.machineCredentials()).singleElement().satisfies(c -> assertThat(c.credential())
                .containsExactly(Map.entry("username", "${env:SR_KEY}"), Map.entry("password", "${env:SR_SECRET}")));
        assertThat(messages(result.report()))
                .contains(
                        "schemaRegistry: pattern payments-.* not imported; list its subjects",
                        "schemaRegistry: register and compatibility not run; jk only downloads")
                .noneMatch(m -> m.contains("kafka-schema-registry-gradle-plugin"));
    }

    @Test
    void scs_multiapi_s_asyncapi_specs_import_as_asyncapi() {
        String model = """
                {"gradle":"9.8.0","rootName":"orders","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"orders","dir":"","group":"com.acme","version":"1.0",
                   "plugins":["java","com.sngular.scs-multiapi-gradle-plugin"],"pluginClasses":[],"pluginVersions":{},
                   "bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[],
                   "asyncApiSpecs":[
                     {"specPath":"src/main/resources/api/event-api.yml","modelPackages":["com.acme.orders.model"],
                      "generatesCode":true},
                     {"modelPackages":[],"generatesCode":false,"specArtifact":"shared-events"}]}
                ]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/orders"), RefreshVersions.NONE);

        assertThat(result.root().pluginConfig("asyncapi").orElseThrow().values())
                .containsOnly(
                        Map.entry("src", "src/main/resources/api/event-api.yml"),
                        Map.entry("package", "com.acme.orders.model"));
        assertThat(messages(result.report()))
                .contains(
                        "scs-multiapi: spec from shared-events not imported; copy it into the tree",
                        "scs-multiapi: consumer and producer code not generated; [asyncapi] makes the models")
                .noneMatch(m -> m.contains("scs-multiapi-gradle-plugin"));
    }

    private static JkBuild module(GradleBuildImport.Result result, String path) {
        return Objects.requireNonNull(result.modules().get(path), path);
    }

    private static List<String> messages(ImportReport report) {
        return report.issues().stream().map(ImportReport.Issue::message).toList();
    }
}
