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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where {@code protobuf-maven-plugin} lands: the {@code [protobuf]} table on a module that owns
 * protos, with protoc's version and the source root, the plugin's output dropped from the root
 * POM's build-helper roots, and rows for the gRPC protoc plugin the preset has no key for.
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
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("runs `grpc-java` protoc plugin (io.grpc:protoc-gen-grpc-java:1.81.0")
                .contains("no `.proto` under `src/main/proto` declares a `service`"));
        assertThat(messages(result)).noneMatch(m -> m.contains("protobuf-maven-plugin</plugin>` was not imported"));

        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered)
                .contains("[protobuf]")
                .contains("version = \"3.25.5\"")
                .contains("src = \"src/main/proto\"");
        assertThat(JkBuildParser.parse(rendered)
                        .pluginConfig("protobuf")
                        .orElseThrow()
                        .string("version"))
                .isEqualTo("3.25.5");
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

    /** A proto with a service needs the gRPC stubs the preset does not generate: Tier 3. */
    @Test
    void a_service_proto_makes_the_grpc_plugin_row_tier_3(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), ROOT.formatted(""));
        Path protoDir = Files.createDirectories(tempDir.resolve("project/src/main/proto/api"));
        Files.writeString(
                protoDir.resolve("service.proto"),
                "syntax = \"proto3\";\nmessage Ping {}\nservice Request {\n  rpc request (Ping) returns (Ping);\n}\n");
        PomImporter.Result result = TestImporters.importXml(tempDir, MODULE.formatted(PLUGIN.formatted("")));

        assertThat(result.jkBuild().pluginConfig("protobuf")).isPresent();
        assertThat(result.report().issues())
                .filteredOn(i -> i.message().contains("protoc plugin"))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity()).isEqualTo(ImportReport.Severity.ERROR);
                    assertThat(issue.message())
                            .contains("`src/main/proto/api/service.proto` declare a `service`")
                            .contains("does not compile");
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
}
