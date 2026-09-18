// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where {@code protobuf-maven-plugin} lands: the {@code [protobuf]} table on a module that owns
 * protos, with protoc's version and the source root, the plugin's output dropped from the root
 * POM's build-helper roots, and {@code compile-custom}'s protoc plugin as a {@code [protobuf.<id>]}
 * entry.
 */
class PomProtobufImportTest {

    private static final String ROOT = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.alibaba.nacos</groupId>
              <artifactId>nacos-all</artifactId>
              <version>3.3.0</version>
              <packaging>pom</packaging>
              <properties>
                <grpc-java.version>1.81.0</grpc-java.version>
                <protobuf-java.version>3.25.5</protobuf-java.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.google.protobuf</groupId>
                    <artifactId>protobuf-java</artifactId>
                    <version>${protobuf-java.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>build-helper-maven-plugin</artifactId>
                    <version>3.2.0</version>
                    <executions>
                      <execution>
                        <id>add-source</id>
                        <goals><goal>add-source</goal></goals>
                        <configuration>
                          <sources><source>target/generated-sources/protobuf/java</source></sources>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin>
                  %s
                </plugins>
              </build>
            </project>
            """;

    private static final String PLUGIN = """
            <plugin>
              <groupId>org.xolstice.maven.plugins</groupId>
              <artifactId>protobuf-maven-plugin</artifactId>
              <version>0.6.1</version>
              <configuration>
                <protocArtifact>com.google.protobuf:protoc:${protobuf-java.version}:exe:${os.detected.classifier}</protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc-java.version}:exe:${os.detected.classifier}</pluginArtifact>
                %s
              </configuration>
              <executions>
                <execution>
                  <goals>
                    <goal>compile</goal>
                    <goal>compile-custom</goal>
                  </goals>
                </execution>
              </executions>
            </plugin>
            """;

    private static final String MODULE = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.alibaba.nacos</groupId>
                <artifactId>nacos-all</artifactId>
                <version>3.3.0</version>
              </parent>
              <artifactId>nacos-consistency</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.google.protobuf</groupId>
                  <artifactId>protobuf-java</artifactId>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  %s
                </plugins>
              </build>
            </project>
            """;

    /** nacos-consistency: the module declares the plugin, the root adds the output as a source root. */
    @Test
    void a_module_owning_protos_gets_the_protobuf_table_and_no_extra_src(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto"));
        Files.writeString(protoDir.resolve("Data.proto"), "syntax = \"proto3\";\nmessage Data { string key = 1; }\n");
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(PLUGIN.formatted("")));

        PluginConfig protobuf = result.jkBuild().pluginConfig("protobuf").orElseThrow();
        assertThat(protobuf.string("version")).isEqualTo("3.25.5");
        assertThat(protobuf.string("src"))
                .as("the preset's own default is `proto`")
                .isEqualTo("src/main/proto");
        assertThat(result.jkBuild().build().extraSrc())
                .as("the output is the preset's contribution")
                .isEmpty();
        assertThat(result.report().hasErrors()).as(messages(result).toString()).isFalse();
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .startsWith("`build-helper-maven-plugin` adds `target/generated-sources/protobuf/java`")
                .contains("no `extra-src` root is written"));
        assertThat(protobuf.entries())
                .as("compile-custom's grpc-java plugin is the [protobuf.grpc-java] entry")
                .containsOnlyKeys("grpc-java");
        assertThat(protobuf.entries().get("grpc-java"))
                .containsEntry("plugin", "io.grpc:protoc-gen-grpc-java:1.81.0")
                .doesNotContainKey("options");
        assertThat(messages(result)).noneMatch(m -> m.contains("protoc plugin"));
        assertThat(messages(result)).noneMatch(m -> m.contains("protobuf-maven-plugin</plugin>` was not imported"));

        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered)
                .contains("[protobuf]")
                .contains("version = \"3.25.5\"")
                .contains("src = \"src/main/proto\"")
                .contains("\n[protobuf.grpc-java]\nplugin = \"io.grpc:protoc-gen-grpc-java:1.81.0\"\n");
        PluginConfig reparsed =
                JkBuildParser.parse(rendered).pluginConfig("protobuf").orElseThrow();
        assertThat(reparsed.string("version")).isEqualTo("3.25.5");
        assertThat(reparsed.entries().get("grpc-java")).containsEntry("plugin", "io.grpc:protoc-gen-grpc-java:1.81.0");
    }

    /** istio's shape: the protos under a resource directory, and options the preset has no key for. */
    @Test
    void the_proto_source_root_is_src_and_uncovered_options_are_one_row(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/resources/proto"));
        Files.writeString(protoDir.resolve("mesh.proto"), "syntax = \"proto3\";\nmessage Mesh {}\n");
        String options = "<useArgumentFile>true</useArgumentFile><checkStaleness>true</checkStaleness>"
                + "<protoSourceRoot>${project.basedir}/src/main/resources/proto</protoSourceRoot>";
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(PLUGIN.formatted(options)));

        PluginConfig protobuf = result.jkBuild().pluginConfig("protobuf").orElseThrow();
        assertThat(protobuf.string("src")).isEqualTo("src/main/resources/proto");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("`<useArgumentFile>`, `<checkStaleness>`")
                .contains("no `[protobuf]` key"));
    }

    /** {@code <pluginParameter>} is the plugin's parameter string: its comma-separated items are the entry's {@code options}. */
    @Test
    void the_plugin_parameter_is_the_entrys_options(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto/api"));
        Files.writeString(
                protoDir.resolve("service.proto"),
                "syntax = \"proto3\";\nmessage Ping {}\nservice Request {\n  rpc request (Ping) returns (Ping);\n}\n");
        String options = "<pluginParameter>@generated=omit,jakarta_omit</pluginParameter>";
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(PLUGIN.formatted(options)));

        PluginConfig protobuf = result.jkBuild().pluginConfig("protobuf").orElseThrow();
        assertThat(protobuf.entries().get("grpc-java"))
                .containsEntry("plugin", "io.grpc:protoc-gen-grpc-java:1.81.0")
                .containsEntry("options", List.of("@generated=omit", "jakarta_omit"));
        assertThat(result.report().hasErrors()).as(messages(result).toString()).isFalse();
        assertThat(messages(result)).noneMatch(m -> m.contains("`<pluginParameter>`"));
        assertThat(JkBuildRenderer.render(result.jkBuild()))
                .contains("[protobuf.grpc-java]\nplugin = \"io.grpc:protoc-gen-grpc-java:1.81.0\"\n"
                        + "options = [\"@generated=omit\", \"jakarta_omit\"]\n");
    }

    /** {@code compile-custom} with a plugin artifact the import cannot resolve is a row: the entry has to be written by hand. */
    @Test
    void an_unresolvable_plugin_artifact_is_a_row(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto"));
        Files.writeString(protoDir.resolve("Data.proto"), "syntax = \"proto3\";\nmessage Data {}\n");
        String plugin = PLUGIN.replace("${grpc-java.version}", "${undefined.grpc.version}");
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(plugin.formatted("")));

        PluginConfig protobuf = result.jkBuild().pluginConfig("protobuf").orElseThrow();
        assertThat(protobuf.entries()).isEmpty();
        assertThat(result.report().issues())
                .filteredOn(i -> i.message().contains("protoc plugin"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity()).isEqualTo(ImportReport.Severity.ERROR);
                    assertThat(issue.message())
                            .contains("`compile-custom` runs the `grpc-java` protoc plugin")
                            .contains("[protobuf.grpc-java]");
                });
    }

    /** The root configures the plugin for every module; one without protos gets neither table nor root. */
    @Test
    void a_module_without_protos_gets_no_table_and_no_root_nothing_fills(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(PLUGIN.formatted("")));
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(""));

        assertThat(result.jkBuild().pluginConfig("protobuf")).isEmpty();
        assertThat(result.jkBuild().build().extraSrc()).isEmpty();
        assertThat(result.report().hasErrors()).as(messages(result).toString()).isFalse();
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .startsWith("`build-helper-maven-plugin` adds `target/generated-sources/protobuf/java`")
                .contains("no `.proto` sources to fill"));
        assertThat(messages(result)).noneMatch(m -> m.contains("protoc plugin"));
    }

    /** No {@code <protocArtifact>}: protoc's release is the protobuf-java dependency's. */
    @Test
    void the_protoc_version_falls_back_to_the_protobuf_java_dependency(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto"));
        Files.writeString(protoDir.resolve("Data.proto"), "syntax = \"proto3\";\nmessage Data {}\n");
        String plugin = """
                <plugin>
                  <groupId>org.xolstice.maven.plugins</groupId>
                  <artifactId>protobuf-maven-plugin</artifactId>
                  <version>0.6.1</version>
                  <executions><execution><goals><goal>compile</goal></goals></execution></executions>
                </plugin>
                """;
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(plugin));

        assertThat(result.jkBuild().pluginConfig("protobuf").orElseThrow().string("version"))
                .isEqualTo("3.25.5");
        assertThat(messages(result)).noneMatch(m -> m.contains("protoc plugin"));
    }

    /**
     * hadoop-common's shape: the {@code compile} execution excludes a proto another generation
     * covers, while the {@code test-compile} execution's excludes are the test protos' business.
     */
    @Test
    void the_compile_executions_excludes_are_the_tables_exclude_list(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto"));
        Files.writeString(protoDir.resolve("ProtobufRpcEngine.proto"), "syntax = \"proto3\";\nmessage Rpc {}\n");
        Files.writeString(protoDir.resolve("ProtobufRpcEngine2.proto"), "syntax = \"proto3\";\nmessage Rpc {}\n");
        String plugin = """
                <plugin>
                  <groupId>org.xolstice.maven.plugins</groupId>
                  <artifactId>protobuf-maven-plugin</artifactId>
                  <version>0.6.1</version>
                  <executions>
                    <execution>
                      <id>src-compile-protoc</id>
                      <goals><goal>compile</goal></goals>
                      <configuration>
                        <excludes><exclude>ProtobufRpcEngine.proto</exclude></excludes>
                      </configuration>
                    </execution>
                    <execution>
                      <id>src-test-compile-protoc</id>
                      <goals><goal>test-compile</goal></goals>
                      <configuration>
                        <excludes><exclude>*legacy.proto</exclude></excludes>
                      </configuration>
                    </execution>
                  </executions>
                </plugin>
                """;
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(plugin));

        PluginConfig protobuf = result.jkBuild().pluginConfig("protobuf").orElseThrow();
        assertThat(protobuf.stringList("exclude"))
                .as("the compile execution's excludes, not the test-compile execution's")
                .containsExactly("ProtobufRpcEngine.proto");
        assertThat(messages(result)).noneMatch(m -> m.contains("`<excludes>`"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("exclude = [\"ProtobufRpcEngine.proto\"]");
        assertThat(JkBuildParser.parse(rendered)
                        .pluginConfig("protobuf")
                        .orElseThrow()
                        .stringList("exclude"))
                .containsExactly("ProtobufRpcEngine.proto");
    }

    /**
     * Hadoop's shape: the root manages a {@code replacer} whose executions are skipped, and a module
     * turns the one over {@code target/generated-sources} back on, so protoc's output is rewritten to
     * the shaded protobuf package before it compiles. That execution is {@code [protobuf] replace};
     * the one over the module's own sources is a row, and the plugin is not the generic row.
     */
    @Test
    void a_replacer_over_the_generated_sources_is_the_tables_replace(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT_WITH_REPLACER);
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto"));
        Files.writeString(protoDir.resolve("Data.proto"), "syntax = \"proto3\";\nmessage Data { string key = 1; }\n");
        String replacer = """
                <plugin>
                  <groupId>com.google.code.maven-replacer-plugin</groupId>
                  <artifactId>replacer</artifactId>
                  <executions>
                    <execution>
                      <id>replace-generated-sources</id>
                      <configuration>
                        <skip>false</skip>
                        <excludes><exclude>**/ProtobufRpcEngineProtos.java</exclude></excludes>
                      </configuration>
                    </execution>
                    <execution>
                      <id>replace-generated-test-sources</id>
                      <configuration><skip>false</skip></configuration>
                    </execution>
                    <execution>
                      <id>replace-sources</id>
                      <configuration><skip>false</skip></configuration>
                    </execution>
                  </executions>
                </plugin>
                """;
        String testProtos = """
                <plugin>
                  <groupId>org.xolstice.maven.plugins</groupId>
                  <artifactId>protobuf-maven-plugin</artifactId>
                  <version>0.6.1</version>
                  <configuration>
                    <protocArtifact>com.google.protobuf:protoc:${protobuf-java.version}:exe:${os.detected.classifier}</protocArtifact>
                  </configuration>
                  <executions>
                    <execution>
                      <goals><goal>compile</goal></goals>
                    </execution>
                    <execution>
                      <id>test-protos</id>
                      <goals><goal>test-compile</goal></goals>
                      <configuration>
                        <outputDirectory>${project.build.directory}/generated-test-sources/java</outputDirectory>
                      </configuration>
                    </execution>
                  </executions>
                </plugin>
                """;
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(testProtos + replacer));

        PluginConfig protobuf = result.jkBuild().pluginConfig("protobuf").orElseThrow();
        assertThat(protobuf.stringMap("replace"))
                .containsExactly(Map.entry("([^\\.])com.google.protobuf", "$1org.apache.hadoop.thirdparty.protobuf"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(JkBuildParser.parse(rendered)
                        .pluginConfig("protobuf")
                        .orElseThrow()
                        .stringMap("replace"))
                .as("the rule round-trips through jk.toml, its quoted key with the dots inside it")
                .isEqualTo(protobuf.stringMap("replace"));
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .startsWith(
                        "`replacer` execution `replace-sources` rewrites the files under `src/main/java` in place"));
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .startsWith("`replacer` execution `replace-generated-sources` `<excludes>`"));
        assertThat(messages(result)).noneMatch(m -> m.contains("`<plugin>replacer</plugin>` was not imported"));
        assertThat(messages(result))
                .as("the test protos' output is not the table's, so the execution over it is a row, not a rule")
                .anySatisfy(m -> assertThat(m)
                        .startsWith("`replacer` execution `replace-generated-test-sources` rewrites the files under"
                                + " `target/generated-test-sources` in place, outside the protobuf plugin's output"));
    }

    private static final String ROOT_WITH_REPLACER = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.alibaba.nacos</groupId>
              <artifactId>nacos-all</artifactId>
              <version>3.3.0</version>
              <packaging>pom</packaging>
              <properties>
                <protobuf-java.version>3.25.5</protobuf-java.version>
                <shaded-protobuf-prefix>org.apache.hadoop.thirdparty.protobuf</shaded-protobuf-prefix>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.google.protobuf</groupId>
                    <artifactId>protobuf-java</artifactId>
                    <version>${protobuf-java.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <groupId>com.google.code.maven-replacer-plugin</groupId>
                      <artifactId>replacer</artifactId>
                      <version>1.5.3</version>
                      <executions>
                        <execution>
                          <id>replace-generated-sources</id>
                          <phase>generate-sources</phase>
                          <goals><goal>replace</goal></goals>
                          <configuration>
                            <skip>true</skip>
                            <basedir>${project.build.directory}/generated-sources</basedir>
                            <includes><include>**/*.java</include></includes>
                            <replacements>
                              <replacement>
                                <token>([^\\.])com.google.protobuf</token>
                                <value>$1${shaded-protobuf-prefix}</value>
                              </replacement>
                            </replacements>
                          </configuration>
                        </execution>
                        <execution>
                          <id>replace-generated-test-sources</id>
                          <phase>generate-test-resources</phase>
                          <goals><goal>replace</goal></goals>
                          <configuration>
                            <skip>true</skip>
                            <basedir>${project.build.directory}/generated-test-sources</basedir>
                            <replacements>
                              <replacement>
                                <token>([^\\.])com.google.protobuf</token>
                                <value>$1${shaded-protobuf-prefix}</value>
                              </replacement>
                            </replacements>
                          </configuration>
                        </execution>
                        <execution>
                          <id>replace-sources</id>
                          <phase>generate-sources</phase>
                          <goals><goal>replace</goal></goals>
                          <configuration>
                            <skip>true</skip>
                            <basedir>${basedir}/src/main/java</basedir>
                            <replacements>
                              <replacement>
                                <token>([^\\.])com.google.protobuf</token>
                                <value>$1${shaded-protobuf-prefix}</value>
                              </replacement>
                            </replacements>
                          </configuration>
                        </execution>
                      </executions>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>
            """;

    /** Every proto excluded leaves the module with none to compile: no table, and the output root row says so. */
    @Test
    void a_module_whose_every_proto_is_excluded_gets_no_table(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto"));
        Files.writeString(protoDir.resolve("Legacy.proto"), "syntax = \"proto3\";\nmessage Rpc {}\n");
        String plugin = """
                <plugin>
                  <groupId>org.xolstice.maven.plugins</groupId>
                  <artifactId>protobuf-maven-plugin</artifactId>
                  <version>0.6.1</version>
                  <configuration><excludes><exclude>**/*.proto</exclude></excludes></configuration>
                  <executions><execution><goals><goal>compile</goal></goals></execution></executions>
                </plugin>
                """;
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(plugin));

        assertThat(result.jkBuild().pluginConfig("protobuf")).isEmpty();
        assertThat(messages(result)).anySatisfy(m -> assertThat(m).contains("no `.proto` sources to fill"));
    }
}
