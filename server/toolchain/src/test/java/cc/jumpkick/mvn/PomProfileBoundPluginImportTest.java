// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin the POM declares bare, whose executions live only in a Maven profile that is not
 * active here, runs under Maven only with {@code -P}: the import writes nothing for it and a
 * Tier-2 row names the profile. A plugin bound in the POM itself is mapped as ever.
 */
class PomProfileBoundPluginImportTest {

    @Test
    void a_native_plugin_bound_only_in_an_inactive_profile_writes_no_native_table(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>examples</module>
                  </modules>
                </project>
                """);
        write(root, "examples/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>examples</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>app</module>
                  </modules>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.graalvm.buildtools</groupId>
                        <artifactId>native-maven-plugin</artifactId>
                        <version>0.10.6</version>
                      </plugin>
                    </plugins>
                  </build>
                  <profiles>
                    <profile>
                      <id>native</id>
                      <build>
                        <pluginManagement>
                          <plugins>
                            <plugin>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot-maven-plugin</artifactId>
                              <executions>
                                <execution>
                                  <id>process-aot</id>
                                  <goals><goal>process-aot</goal></goals>
                                </execution>
                              </executions>
                            </plugin>
                            <plugin>
                              <groupId>org.graalvm.buildtools</groupId>
                              <artifactId>native-maven-plugin</artifactId>
                              <executions>
                                <execution>
                                  <id>add-reachability-metadata</id>
                                  <goals><goal>add-reachability-metadata</goal></goals>
                                </execution>
                              </executions>
                            </plugin>
                          </plugins>
                        </pluginManagement>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """);
        write(root, "examples/app/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>examples</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                        <version>3.5.5</version>
                        <executions>
                          <execution>
                            <goals><goal>repackage</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        JkBuild app = requireNonNull(result.modules().get("examples/app"));
        List<String> rows = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();

        assertThat(app.nativeConfigOpt())
                .as("no `[native]`, so Spring AOT stays off")
                .isEmpty();
        assertThat(app.pluginConfig("spring-boot")).as("the Boot table stays").isPresent();
        assertThat(rows)
                .anyMatch(m -> m.startsWith("[examples/app] `native-maven-plugin` is declared without executions or"
                        + " configuration; Maven profile `native` supplies them, and that profile is not active on"
                        + " this machine, so the plugin runs only under `-P native`; no `[native]` table is written."));
        assertThat(result.report().issues().stream()
                        .filter(i -> i.message().contains("`native-maven-plugin` is declared without"))
                        .map(ImportReport.Issue::severity))
                .containsExactly(ImportReport.Severity.WARNING);
    }

    @Test
    void the_profile_row_lists_the_plugin_management_executions_it_binds(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-shade-plugin</artifactId>
                        <version>3.6.0</version>
                      </plugin>
                    </plugins>
                  </build>
                  <profiles>
                    <profile>
                      <id>dist</id>
                      <build>
                        <pluginManagement>
                          <plugins>
                            <plugin>
                              <groupId>org.apache.maven.plugins</groupId>
                              <artifactId>maven-shade-plugin</artifactId>
                              <executions>
                                <execution>
                                  <phase>package</phase>
                                  <goals><goal>shade</goal></goals>
                                  <configuration>
                                    <transformers>
                                      <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                        <mainClass>org.demo.Main</mainClass>
                                      </transformer>
                                    </transformers>
                                  </configuration>
                                </execution>
                              </executions>
                            </plugin>
                          </plugins>
                        </pluginManagement>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """);

        assertThat(result.jkBuild().applicationOpt())
                .as("no fat jar without the profile")
                .isEmpty();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`maven-shade-plugin` is declared without executions or configuration;"
                        + " Maven profile `dist` supplies them"))
                .anyMatch(m -> m.equals("Maven profile `dist`: plugins=[maven-shade-plugin] — port by hand;"
                        + " docs/user/migration.md lists where each plugin lands."));
    }

    @Test
    void a_plugin_bound_in_the_pom_itself_is_mapped(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>cli</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.graalvm.buildtools</groupId>
                        <artifactId>native-maven-plugin</artifactId>
                        <version>0.10.6</version>
                        <configuration>
                          <imageName>cli</imageName>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                  <profiles>
                    <profile>
                      <id>native</id>
                      <build>
                        <plugins>
                          <plugin>
                            <groupId>org.graalvm.buildtools</groupId>
                            <artifactId>native-maven-plugin</artifactId>
                            <executions>
                              <execution>
                                <goals><goal>compile-no-fork</goal></goals>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """);

        assertThat(result.jkBuild().nativeConfigOpt())
                .as("its own configuration binds it, whatever the profile adds")
                .hasValueSatisfying(nc -> assertThat(nc.name()).isEqualTo("cli"));
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("is declared without executions"));
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
