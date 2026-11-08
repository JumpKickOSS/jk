// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The AsyncAPI code generators of a POM as [asyncapi]: their documents and model package, rows for the rest. */
class PomAsyncApiImportTest {

    @Test
    void scs_multiapi_asyncapi_generation_becomes_the_asyncapi_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.sngular</groupId>
                        <artifactId>scs-multiapi-maven-plugin</artifactId>
                        <version>8.4.0</version>
                        <executions>
                          <execution>
                            <id>asyncapi</id>
                            <goals><goal>asyncapi-generation</goal></goals>
                            <configuration>
                              <specFiles>
                                <specFile>
                                  <filePath>${project.basedir}/src/main/resources/api/event-api.yml</filePath>
                                  <consumer>
                                    <ids>publishOperation</ids>
                                    <modelPackage>com.acme.orders.model</modelPackage>
                                  </consumer>
                                </specFile>
                              </specFiles>
                            </configuration>
                          </execution>
                          <execution>
                            <id>openapi</id>
                            <goals><goal>openapi-generation</goal></goals>
                            <configuration>
                              <specFiles><specFile><filePath>src/main/resources/api/rest.yml</filePath></specFile></specFiles>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("asyncapi").orElseThrow().values())
                .containsOnly(
                        Map.entry("src", "src/main/resources/api/event-api.yml"),
                        Map.entry("package", "com.acme.orders.model"));
        assertThat(messages(result))
                .contains("scs-multiapi-maven-plugin: consumer and producer code not generated; [asyncapi] makes"
                        + " the models")
                .noneMatch(m -> m.contains("scs-multiapi-maven-plugin not imported"));
    }

    @Test
    void zenwave_s_jsonschema2pojo_generator_becomes_the_asyncapi_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>io.zenwave360.sdk</groupId>
                        <artifactId>zenwave-sdk-maven-plugin</artifactId>
                        <version>2.8.1</version>
                        <executions>
                          <execution>
                            <id>models</id>
                            <goals><goal>generate</goal></goals>
                            <configuration>
                              <generatorName>jsonschema2pojo</generatorName>
                              <inputSpec>${pom.basedir}/src/main/asyncapi/asyncapi.yaml</inputSpec>
                              <configOptions><modelPackage>com.acme.events</modelPackage></configOptions>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("asyncapi").orElseThrow().values())
                .containsOnly(Map.entry("package", "com.acme.events"));
        assertThat(messages(result)).noneMatch(m -> m.contains("zenwave-sdk-maven-plugin"));
    }
}
