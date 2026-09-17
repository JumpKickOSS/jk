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
     * zipkin-server's shape: maven-dependency-plugin unpacks the {@code .proto} files out of
     * {@code io.zipkin.proto3:zipkin-proto3}, wire-maven-plugin generates Java from them at
     * generate-test-sources, and build-helper adds the output as a test root. Both plugins land
     * as one {@code [generate.wire]} recipe over {@code com.squareup.wire:wire-compiler} at the
     * module's wire-runtime version, the unpacked jar its proto source and the output a
     * test-sources contribution, so no {@code [test] extra-src} root is written.
     */
    @Test
    void wire_plugin_over_unpacked_protos_becomes_a_generate_recipe_for_the_test_compile(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.zipkin</groupId>
                  <artifactId>zipkin-server</artifactId>
                  <version>3.6.2</version>
                  <properties>
                    <unpack-proto.directory>${project.build.directory}/test/proto</unpack-proto.directory>
                    <proto.generatedSourceDirectory>${project.build.directory}/generated-test-sources/wire</proto.generatedSourceDirectory>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.squareup.wire</groupId>
                      <artifactId>wire-runtime-jvm</artifactId>
                      <version>5.5.1</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>io.zipkin.proto3</groupId>
                      <artifactId>zipkin-proto3</artifactId>
                      <version>1.0.0</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-dependency-plugin</artifactId>
                        <version>3.8.1</version>
                        <executions>
                          <execution>
                            <id>unpack-proto</id>
                            <phase>generate-sources</phase>
                            <goals><goal>unpack-dependencies</goal></goals>
                            <configuration>
                              <includeArtifactIds>zipkin-proto3</includeArtifactIds>
                              <includes>**/*.proto</includes>
                              <outputDirectory>${unpack-proto.directory}</outputDirectory>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <groupId>de.m3y.maven</groupId>
                        <artifactId>wire-maven-plugin</artifactId>
                        <version>1.3</version>
                        <executions>
                          <execution>
                            <phase>generate-test-sources</phase>
                            <goals><goal>generate-sources</goal></goals>
                            <configuration>
                              <protoSourceDirectory>${unpack-proto.directory}</protoSourceDirectory>
                              <includes><include>zipkin.proto3.*</include></includes>
                              <generatedSourceDirectory>${proto.generatedSourceDirectory}</generatedSourceDirectory>
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
                            <id>add-test-source</id>
                            <phase>generate-test-sources</phase>
                            <goals><goal>add-test-source</goal></goals>
                            <configuration>
                              <sources><source>${proto.generatedSourceDirectory}</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        JkBuild build = result.jkBuild();

        PluginConfig generate = build.pluginConfig("generator").orElseThrow();
        Map<String, Object> wire = generate.entries().get("wire");
        assertThat(wire).isNotNull();
        assertThat(wire)
                .containsEntry("tool", "com.squareup.wire:wire-compiler:5.5.1")
                .containsEntry("main", "com.squareup.wire.WireCompiler")
                .containsEntry("unpack", "io.zipkin.proto3:zipkin-proto3:1.0.0")
                .containsEntry("contributes", "test-sources")
                .containsEntry(
                        "args", List.of("--proto_path=${unpacked}", "--java_out=${out}", "--includes=zipkin.proto3.*"))
                .doesNotContainKey("inputs");
        assertThat(build.build().testExtraSrc())
                .as("the recipe's output is its own contribution")
                .isEmpty();

        List<String> rows = messages(result);
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("`build-helper-maven-plugin` adds `target/generated-test-sources/wire`")
                .contains("`[generate.wire]`"));
        assertThat(rows)
                .noneMatch(m -> m.contains("wire-maven-plugin</plugin>` was not imported"))
                .noneMatch(m -> m.contains("maven-dependency-plugin</plugin>` was not imported"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[generate.wire]")
                .contains("tool = \"com.squareup.wire:wire-compiler:5.5.1\"")
                .contains("unpack = \"io.zipkin.proto3:zipkin-proto3:1.0.0\"")
                .contains("contributes = \"test-sources\"");
        Map<String, Object> reparsed = JkBuildParser.parse(rendered)
                .pluginConfig("generator")
                .orElseThrow()
                .entries()
                .get("wire");
        assertThat(reparsed).containsAllEntriesOf(wire);
    }

    /** The GraphQL code generators are rows naming the recipe, not a table. */
    @Test
    void graphql_codegen_plugins_are_rows_naming_the_recipe(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>gql</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>io.github.deweyjose</groupId>
                        <artifactId>graphqlcodegen-maven-plugin</artifactId>
                        <version>3.10.1</version>
                      </plugin>
                      <plugin>
                        <groupId>io.github.kobylynskyi</groupId>
                        <artifactId>graphql-codegen-maven-plugin</artifactId>
                        <version>5.10.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfigs()).isEmpty();
        List<String> rows = messages(result);
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("`graphqlcodegen-maven-plugin`")
                .contains("`[generate.<name>]` recipe")
                .contains("graphql-dgs-codegen-core"));
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("`graphql-codegen-maven-plugin`")
                .contains("no command line")
                .contains("jk mvn"));
        assertThat(rows).noneMatch(m -> m.contains("was not imported"));
    }

    /** A wire plugin over protos in the tree, at generate-sources, with no wire-runtime dependency. */
    @Test
    void wire_plugin_over_tree_protos_is_a_recipe_over_the_latest_compiler(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(project.resolve("src/main/proto"));
        Files.writeString(project.resolve("src/main/proto/a.proto"), "syntax = \"proto3\";\n");
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>msgs</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.squareup.wire</groupId>
                        <artifactId>wire-maven-plugin</artifactId>
                        <version>4.9.9</version>
                        <executions>
                          <execution>
                            <goals><goal>generate-sources</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        Map<String, Object> wire = result.jkBuild()
                .pluginConfig("generator")
                .orElseThrow()
                .entries()
                .get("wire");
        assertThat(wire)
                .containsEntry("tool", "com.squareup.wire:wire-compiler:latest")
                .containsEntry("inputs", List.of("src/main/proto/**/*.proto"))
                .containsEntry("args", List.of("--proto_path=${module.dir}/src/main/proto", "--java_out=${out}"))
                .doesNotContainKey("unpack")
                .doesNotContainKey("contributes");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("`wire-maven-plugin`")
                .contains("no wire-runtime dependency")
                .contains("latest"));
    }

    /**
     * jenkins's {@code cli} shape: localizer-maven-plugin generates {@code hudson.cli.client.Messages}
     * from a resource bundle and build-helper adds the output as a source root. The plugin lands as
     * the {@code [localizer]} preset, whose step generates the classes into the compile, so the
     * output is not written as an {@code extra-src} root.
     */
    @Test
    void localizer_plugin_becomes_the_localizer_preset(@TempDir Path tempDir) throws Exception {
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
                              <accessModifierAnnotations>true</accessModifierAnnotations>
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
                .as("the preset's output is its own contribution")
                .isEmpty();
        PluginConfig localizer = result.jkBuild().pluginConfig("localizer").orElseThrow();
        assertThat(localizer.values())
                .containsEntry("access-modifier-annotations", true)
                .containsEntry("version", "1.31")
                .doesNotContainKey("mask")
                .doesNotContainKey("resources");
        assertThat(result.report().issues()).noneMatch(i -> i.severity() == ImportReport.Severity.ERROR);
        assertThat(messages(result))
                .anyMatch(m -> m.startsWith("`build-helper-maven-plugin` adds `target/generated-sources/localizer`, the"
                        + " localizer preset's output"))
                .anyMatch(m -> m.contains("`[localizer]`") && m.contains("`org.jvnet.localizer:localizer`"))
                .noneMatch(m -> m.contains("`<plugin>localizer-maven-plugin</plugin>` was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[localizer]").contains("access-modifier-annotations = true");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("localizer")).isPresent();
    }

    /** A mask, resource directories and encoding the POM sets travel into the table. */
    @Test
    void localizer_configuration_travels_into_the_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId>
                  <artifactId>msgs</artifactId>
                  <version>1.0</version>
                  <build>
                    <resources>
                      <resource><directory>src/main/resources</directory></resource>
                      <resource><directory>src/main/i18n</directory></resource>
                    </resources>
                    <plugins>
                      <plugin>
                        <groupId>org.jvnet.localizer</groupId>
                        <artifactId>localizer-maven-plugin</artifactId>
                        <version>1.31</version>
                        <configuration>
                          <outputEncoding>ISO-8859-1</outputEncoding>
                        </configuration>
                        <executions>
                          <execution>
                            <goals><goal>generate</goal></goals>
                            <configuration>
                              <fileMask>*.properties</fileMask>
                              <keyPattern>[a-z.]+</keyPattern>
                              <strictTypes>true</strictTypes>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig localizer = result.jkBuild().pluginConfig("localizer").orElseThrow();
        assertThat(localizer.values())
                .containsEntry("mask", "*.properties")
                .containsEntry("resources", List.of("src/main/resources", "src/main/i18n"))
                .containsEntry("encoding", "ISO-8859-1")
                .containsEntry("key-pattern", "[a-z.]+")
                .containsEntry("strict-types", true)
                .doesNotContainKey("access-modifier-annotations");
    }

    /** jenkins core's shape: the plugin with one antlr4 execution, build-helper adding its output. */
    @Test
    void antlr4_plugin_becomes_the_antlr_preset(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jenkins-ci.main</groupId>
                  <artifactId>jenkins-core</artifactId>
                  <version>2.583</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.antlr</groupId>
                        <artifactId>antlr4-maven-plugin</artifactId>
                        <version>4.13.2</version>
                        <executions>
                          <execution>
                            <id>antlr</id>
                            <goals><goal>antlr4</goal></goals>
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
                              <sources><source>${project.build.directory}/generated-sources/antlr4</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().build().extraSrc())
                .as("the preset's output is its own contribution")
                .isEmpty();
        PluginConfig antlr = result.jkBuild().pluginConfig("antlr").orElseThrow();
        assertThat(antlr.values()).as("every default stays unwritten").isEmpty();
        assertThat(result.report().issues()).noneMatch(i -> i.severity() == ImportReport.Severity.ERROR);
        assertThat(messages(result))
                .anyMatch(m -> m.startsWith("`build-helper-maven-plugin` adds `target/generated-sources/antlr4`, the"
                        + " antlr preset's output"))
                .anyMatch(m -> m.contains("`[antlr]`") && m.contains("`org.antlr:antlr4-runtime`"))
                .noneMatch(m -> m.contains("`<plugin>antlr4-maven-plugin</plugin>` was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[antlr]");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("antlr")).isPresent();
    }

    /** Every configured option travels into the table; the ones the preset has no key for are a row. */
    @Test
    void antlr_configuration_travels_into_the_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId>
                  <artifactId>parser</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.antlr</groupId>
                        <artifactId>antlr4-maven-plugin</artifactId>
                        <version>4.12.0</version>
                        <configuration>
                          <sourceDirectory>${basedir}/src/main/antlr</sourceDirectory>
                          <libDirectory>${basedir}/src/main/antlr-lib</libDirectory>
                          <outputDirectory>${project.build.directory}/generated-sources/parsers</outputDirectory>
                          <listener>false</listener>
                          <visitor>true</visitor>
                          <inputEncoding>ISO-8859-1</inputEncoding>
                          <treatWarningsAsErrors>true</treatWarningsAsErrors>
                          <arguments><argument>-Xexact-output-dir</argument></arguments>
                          <options><superClass>com.acme.Base</superClass></options>
                          <includes><include>**/*.g4</include></includes>
                        </configuration>
                        <executions>
                          <execution><goals><goal>antlr4</goal></goals></execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig antlr = result.jkBuild().pluginConfig("antlr").orElseThrow();
        assertThat(antlr.values())
                .containsEntry("src", "src/main/antlr")
                .containsEntry("lib", "src/main/antlr-lib")
                .containsEntry("listener", false)
                .containsEntry("visitor", true)
                .containsEntry("encoding", "ISO-8859-1")
                .containsEntry("arguments", List.of("-Werror", "-Xexact-output-dir"))
                .containsEntry("options", Map.of("superClass", "com.acme.Base"))
                .containsEntry("version", "4.12.0");
        assertThat(messages(result)).anyMatch(m -> m.contains("`<includes>` have no `[antlr]` key"));
    }

    /** jenkins core's shape: the hpi plugin's taglib goal beside another goal, build-helper adding the output. */
    @Test
    void hpi_taglib_interface_goal_becomes_the_taglib_preset(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jenkins-ci.main</groupId>
                  <artifactId>jenkins-core</artifactId>
                  <version>2.583</version>
                  <build>
                    <resources>
                      <resource><directory>src/main/resources</directory></resource>
                      <resource><directory>src/filter/resources</directory></resource>
                    </resources>
                    <plugins>
                      <plugin>
                        <groupId>org.jenkins-ci.tools</groupId>
                        <artifactId>maven-hpi-plugin</artifactId>
                        <version>3.1838.va_13472137a_6a_</version>
                        <executions>
                          <execution>
                            <goals>
                              <goal>generate-taglib-interface</goal>
                              <goal>record-core-location</goal>
                            </goals>
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
                              <sources><source>${project.build.directory}/generated-sources/taglib-interface</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().build().extraSrc()).isEmpty();
        PluginConfig taglib = result.jkBuild().pluginConfig("taglib").orElseThrow();
        assertThat(taglib.values()).containsEntry("resources", List.of("src/main/resources", "src/filter/resources"));
        assertThat(messages(result))
                .anyMatch(m ->
                        m.startsWith("`build-helper-maven-plugin` adds `target/generated-sources/taglib-interface`,"
                                + " the taglib preset's output"))
                .anyMatch(m -> m.contains("`[taglib]`") && m.contains("`org.kohsuke.stapler:stapler-groovy`"))
                .anyMatch(m -> m.startsWith("`maven-hpi-plugin` goal `record-core-location` was not imported"))
                .noneMatch(m -> m.contains("`<plugin>maven-hpi-plugin</plugin>` was not imported"));
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(result.jkBuild())).pluginConfig("taglib"))
                .isPresent();
    }

    /** A Jenkins plugin packaged by the hpi plugin without the taglib goal keeps the plugin's generic row. */
    @Test
    void hpi_plugin_without_the_taglib_goal_stays_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jenkins-ci.plugins</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jenkins-ci.tools</groupId>
                        <artifactId>maven-hpi-plugin</artifactId>
                        <version>3.1838.va_13472137a_6a_</version>
                        <extensions>true</extensions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("taglib")).isEmpty();
        assertThat(messages(result)).anyMatch(m -> m.contains("`<plugin>maven-hpi-plugin</plugin>` was not imported"));
    }
}
