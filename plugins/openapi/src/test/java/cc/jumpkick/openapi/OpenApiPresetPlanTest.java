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
