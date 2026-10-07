// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code [openapi]} table as the generator entry it expands to, and what that declares. */
class OpenApiPresetPlanTest {

    private static final ProjectFacts PROJECT =
            new ProjectFacts("com.acme", "svc", "1.0.0", 25, null, false, false, Map.of());

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new OpenApiPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-openapi");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKOA:");
    }

    @Test
    void spring_gets_the_interface_only_defaults_and_the_table_overrides_them() {
        GeneratorEntry entry = OpenApiPreset.entry(
                new PluginConfig(
                        "openapi",
                        Map.of(
                                "spec", "api/openapi.yaml",
                                "generator", "spring",
                                "package", "com.acme.api",
                                "options", Map.of("useTags", "false"))),
                PROJECT);

        assertThat(entry.name()).isEqualTo("openapi");
        assertThat(entry.stepName()).isEqualTo("generate-openapi");
        assertThat(entry.toolArtifact()).isEqualTo("openapi-generator-cli");
        assertThat(entry.main()).isNull();
        assertThat(entry.inputs()).containsExactly("api/openapi.yaml");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.args())
                .containsExactly(
                        "generate",
                        "-i",
                        "${in}",
                        "-g",
                        "spring",
                        "-o",
                        "${out}",
                        "--api-package",
                        "com.acme.api",
                        "--model-package",
                        "com.acme.api.model",
                        "--invoker-package",
                        "com.acme.api",
                        "--package-name",
                        "com.acme.api",
                        "--additional-properties",
                        "interfaceOnly=true,useSpringBoot3=true,useJakartaEe=true,documentationProvider=none,"
                                + "annotationLibrary=none,openApiNullable=false,useTags=false");
    }

    /** apollo-portal's layout: api at {@code <root>.api}, models at {@code <root>.model}, invoker at {@code <root>.invoker}. */
    @Test
    void each_package_key_replaces_the_name_the_root_derives() {
        GeneratorEntry entry = OpenApiPreset.entry(
                new PluginConfig(
                        "openapi",
                        Map.of(
                                "generator", "spring",
                                "package", "com.ctrip.framework.apollo.openapi",
                                "api-package", "com.ctrip.framework.apollo.openapi.api",
                                "invoker-package", "com.ctrip.framework.apollo.openapi.invoker")),
                PROJECT);

        assertThat(entry.args())
                .containsSubsequence(
                        "--api-package",
                        "com.ctrip.framework.apollo.openapi.api",
                        "--model-package",
                        "com.ctrip.framework.apollo.openapi.model",
                        "--invoker-package",
                        "com.ctrip.framework.apollo.openapi.invoker",
                        "--package-name",
                        "com.ctrip.framework.apollo.openapi");
    }

    /** The generator refuses `useSpringBoot3` beside `useSpringBoot4`; asking for Boot 4 drops the preset's Boot 3 default. */
    @Test
    void asking_for_spring_boot_4_drops_the_boot_3_default() {
        GeneratorEntry entry = OpenApiPreset.entry(
                new PluginConfig("openapi", Map.of("generator", "spring", "options", Map.of("useSpringBoot4", "true"))),
                PROJECT);

        String properties = entry.args().getLast();
        assertThat(properties).contains("useSpringBoot4=true").doesNotContain("useSpringBoot3");
    }

    /**
     * The generator refuses a documentation provider beside an annotation library it does not
     * support; naming a provider leaves the library to the generator, as a Maven build does.
     */
    @Test
    void naming_a_documentation_provider_drops_the_annotation_library_default() {
        GeneratorEntry entry = OpenApiPreset.entry(
                new PluginConfig(
                        "openapi",
                        Map.of("generator", "spring", "options", Map.of("documentationProvider", "springdoc"))),
                PROJECT);

        String properties = entry.args().getLast();
        assertThat(properties).contains("documentationProvider=springdoc").doesNotContain("annotationLibrary");

        GeneratorEntry both = OpenApiPreset.entry(
                new PluginConfig(
                        "openapi",
                        Map.of(
                                "generator",
                                "spring",
                                "options",
                                Map.of("documentationProvider", "springdoc", "annotationLibrary", "swagger2"))),
                PROJECT);
        assertThat(both.args().getLast()).contains("annotationLibrary=swagger2");
    }

    @Test
    void library_model_names_and_mappings_are_their_own_flags() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("generator", "spring");
        table.put("library", "spring-boot");
        table.put("model-name-prefix", "Api");
        table.put("model-name-suffix", "Dto");
        table.put("import-mappings", Map.of("Nullable", "org.jspecify.annotations.Nullable"));
        table.put("type-mappings", Map.of("DateTime", "java.time.Instant"));
        List<String> args =
                OpenApiPreset.entry(new PluginConfig("openapi", table), PROJECT).args();

        assertThat(args).containsSubsequence("--library", "spring-boot");
        assertThat(args).containsSubsequence("--model-name-prefix", "Api");
        assertThat(args).containsSubsequence("--model-name-suffix", "Dto");
        assertThat(args).containsSubsequence("--import-mappings", "Nullable=org.jspecify.annotations.Nullable");
        assertThat(args).containsSubsequence("--type-mappings", "DateTime=java.time.Instant");
        assertThat(args.get(args.size() - 2)).isEqualTo("--additional-properties");
    }

    @Test
    void another_generator_gets_only_the_tables_options_and_the_group_package() {
        GeneratorEntry entry = OpenApiPreset.entry(
                new PluginConfig("openapi", Map.of("generator", "java", "options", Map.of("library", "native"))),
                PROJECT);

        assertThat(entry.inputs()).containsExactly("api/*.yaml");
        assertThat(entry.args())
                .contains("--api-package", "com.acme.api", "--model-package", "com.acme.api.model")
                .endsWith("--additional-properties", "library=native");
    }

    @Test
    void describe_shows_the_expanded_step(@TempDir Path dir) throws Exception {
        Path spec = dir.resolve("describe.spec");
        Files.write(
                spec,
                List.of(
                        "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-openapi\"}",
                        "{\"t\":\"config\",\"key\":\"spec\",\"kind\":\"string\",\"value\":\"api/openapi.yaml\"}",
                        "{\"t\":\"config\",\"key\":\"generator\",\"kind\":\"string\",\"value\":\"spring\"}",
                        "{\"t\":\"project\",\"group\":\"com.acme\",\"name\":\"svc\",\"version\":\"1\","
                                + "\"javaRelease\":25,\"nativeDeclared\":false,\"kotlin\":false}"));
        var buffer = new ByteArrayOutputStream();
        var writer = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKOA:");
        assertThat(new OpenApiPreset().run(List.of(spec.toString()), writer)).isZero();

        String task = List.of(buffer.toString(StandardCharsets.UTF_8).split("\n")).stream()
                .filter(l -> l.contains("\"t\":\"task\""))
                .findFirst()
                .orElseThrow();
        assertThat(task)
                .contains("\"name\":\"generate-openapi\"")
                .contains("\"inputs\":[\"project:api/openapi.yaml\",\"config\"]")
                .contains("\"outputs\":[\"generated/openapi\"]")
                .contains("\"contributesSources\":[\"generated/openapi\"]")
                .contains("\"stage\":\"generate\"");
    }
}
