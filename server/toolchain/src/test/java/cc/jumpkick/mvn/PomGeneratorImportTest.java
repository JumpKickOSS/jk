// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where {@code openapi-generator-maven-plugin} lands: the {@code [openapi]} preset with the spec,
 * generator, package root, CLI version and options, a remote spec fetched beside the manifest, the
 * generator's output dropped from build-helper's roots, and a row for each option the preset has
 * no key for.
 */
class PomGeneratorImportTest {

    private static final String SPEC = "openapi: 3.0.0\ninfo:\n  title: apollo\n";

    /** apollo-portal's shape: the configuration under pluginManagement, the spec a URL, build-helper adding the output. */
    @Test
    void openapi_generator_plugin_becomes_the_openapi_preset(@TempDir Path tempDir) throws Exception {
        List<URI> fetched = new ArrayList<>();
        PomImporter.Result result = importFixture(tempDir, uri -> {
            fetched.add(uri);
            return SPEC.getBytes(StandardCharsets.UTF_8);
        });
        JkBuild build = result.jkBuild();

        PluginConfig openapi = build.pluginConfig("openapi").orElseThrow();
        assertThat(openapi.string("spec")).isEqualTo("api/apollo-openapi.yaml");
        assertThat(openapi.string("generator")).isEqualTo("spring");
        assertThat(openapi.string("package")).isEqualTo("com.ctrip.framework.apollo.openapi");
        assertThat(openapi.string("api-package")).isEqualTo("com.ctrip.framework.apollo.openapi.api");
        assertThat(openapi.stringOpt("model-package")).as("the root derives it").isEmpty();
        assertThat(openapi.string("invoker-package")).isEqualTo("com.ctrip.framework.apollo.openapi.invoker");
        assertThat(openapi.string("version")).isEqualTo("7.20.0");
        assertThat(openapi.stringMap("options"))
                .containsExactly(
                        Map.entry("interfaceOnly", "true"),
                        Map.entry("useTags", "true"),
                        Map.entry("dateLibrary", "java8"));
        assertThat(fetched).containsExactly(URI.create("http://spec.test/apollo-openapi/v0.3.11/apollo-openapi.yaml"));
        assertThat(tempDir.resolve("project/api/apollo-openapi.yaml")).content().isEqualTo(SPEC);
        assertThat(build.build().extraSrc())
                .as("the generator's output is its own contribution; the other root stays")
                .containsExactly("src/generated/java");

        List<String> rows = messages(result);
        assertThat(rows).anySatisfy(m -> assertThat(m).contains("fetched to `api/apollo-openapi.yaml`"));
        assertThat(rows)
                .anySatisfy(m -> assertThat(m).contains("`<skipValidateSpec>`").contains("no `[openapi]` key"));
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("target/generated-sources/openapi/src/main/java")
                .contains("no `extra-src` root is written"));
        assertThat(rows).noneMatch(m -> m.contains("openapi-generator-maven-plugin</plugin>` was not imported"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[openapi]")
                .contains("spec = \"api/apollo-openapi.yaml\"")
                .contains("api-package = \"com.ctrip.framework.apollo.openapi.api\"")
                .doesNotContain("model-package")
                .contains("options = { interfaceOnly = \"true\", useTags = \"true\", dateLibrary = \"java8\" }");
        PluginConfig reparsed =
                JkBuildParser.parse(rendered).pluginConfig("openapi").orElseThrow();
        assertThat(reparsed.stringMap("options")).isEqualTo(openapi.stringMap("options"));
        assertThat(reparsed.string("package")).isEqualTo("com.ctrip.framework.apollo.openapi");
        assertThat(reparsed.string("invoker-package")).isEqualTo("com.ctrip.framework.apollo.openapi.invoker");
    }

    @Test
    void a_spec_that_cannot_be_fetched_is_a_tier_3_row_and_the_table_still_names_the_file(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = importFixture(tempDir, uri -> {
            throw new IOException("HTTP 503");
        });

        assertThat(result.jkBuild().pluginConfig("openapi").orElseThrow().string("spec"))
                .isEqualTo("api/apollo-openapi.yaml");
        assertThat(tempDir.resolve("project/api/apollo-openapi.yaml")).doesNotExist();
        assertThat(result.report().hasErrors()).isTrue();
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("could not be fetched (HTTP 503)")
                .contains("save the spec there before building"));
    }

    @Test
    void a_spec_already_beside_the_manifest_is_kept(@TempDir Path tempDir) throws Exception {
        Path existing = Files.createDirectories(tempDir.resolve("project/api")).resolve("apollo-openapi.yaml");
        Files.writeString(existing, "mine");
        PomImporter.Result result = importFixture(tempDir, uri -> {
            throw new AssertionError("no fetch when the file is there");
        });

        assertThat(existing).content().isEqualTo("mine");
        assertThat(messages(result))
                .anySatisfy(m -> assertThat(m).contains("already there").contains("was not fetched"));
    }

    @Test
    void a_file_spec_is_written_module_relative(@TempDir Path tempDir) throws Exception {
        String xml = TestImporters.fixture("plugins", "openapi-pom.xml")
                .replace(
                        "<inputSpec>${apollo.openapi.spec.url}</inputSpec>",
                        "<inputSpec>${project.basedir}/src/main/resources/openapi.yaml</inputSpec>");
        PomImporter.Result result = TestImporters.importXml(tempDir, xml, uri -> {
            throw new AssertionError("a file spec is not fetched");
        });

        assertThat(result.jkBuild().pluginConfig("openapi").orElseThrow().string("spec"))
                .isEqualTo("src/main/resources/openapi.yaml");
    }

    private static PomImporter.Result importFixture(Path tempDir, PomImporter.RemoteFile remote) throws IOException {
        return TestImporters.importXml(tempDir, TestImporters.fixture("plugins", "openapi-pom.xml"), remote);
    }

    /**
     * jenkins's {@code cli} shape: localizer-maven-plugin generates {@code hudson.cli.client.Messages}
     * from a resource bundle and build-helper adds the output as a source root. The plugin has no
     * entry point jk can run, so the module gets a Tier-3 row naming the class, and the output is
     * not written as an {@code extra-src} root nothing would fill.
     */
    @Test
    void localizer_plugin_is_a_tier_3_row_naming_the_generated_classes(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path bundle = Files.createDirectories(project.resolve("src/main/resources/hudson/cli/client"));
        Files.writeString(bundle.resolve("Messages.properties"), "CLI.Usage=Jenkins CLI\n");
        Files.writeString(bundle.resolve("Messages_de.properties"), "CLI.Usage=Jenkins-CLI\n");
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jenkins-ci.main</groupId>
                  <artifactId>cli</artifactId>
                  <version>2.583</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jvnet.localizer</groupId>
                        <artifactId>localizer-maven-plugin</artifactId>
                        <version>1.31</version>
                        <executions>
                          <execution>
                            <goals><goal>generate</goal></goals>
                            <configuration>
                              <fileMask>Messages.properties</fileMask>
                              <outputDirectory>target/generated-sources/localizer</outputDirectory>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.6.0</version>
                        <executions>
                          <execution>
                            <goals><goal>add-source</goal></goals>
                            <configuration>
                              <sources><source>target/generated-sources/localizer</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().build().extraSrc())
                .as("a root nothing fills is not written")
                .isEmpty();
        assertThat(result.report().issues())
                .filteredOn(i -> i.message().startsWith("`localizer-maven-plugin` generates"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity()).isEqualTo(ImportReport.Severity.ERROR);
                    assertThat(issue.message())
                            .contains("`hudson.cli.client.Messages`")
                            .doesNotContain("Messages_de")
                            .contains("`target/generated-sources/localizer`")
                            .contains("jk mvn generate-sources");
                });
        assertThat(messages(result))
                .anyMatch(m -> m.startsWith("`build-helper-maven-plugin` adds `target/generated-sources/localizer`, the"
                        + " localizer plugin's output"))
                .noneMatch(m -> m.contains("`<plugin>localizer-maven-plugin</plugin>` was not imported"));
    }
}
