// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the schema compilers land: {@code avro-maven-plugin} as the {@code [avro]} preset, the two
 * JAXB plugins as {@code [jaxb]} and {@code jooq-codegen-maven} as {@code [jooq]}, each with its
 * configuration in the table's keys, its output dropped from build-helper's roots, and a row for
 * what the preset has no key for.
 */
class PomSchemaGeneratorImportTest {

    /** The Baeldung Kafka module's shape: the plugin bound to `schema` with the Maven defaults. */
    @Test
    void avro_plugin_becomes_the_avro_preset(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.baeldung</groupId>
                  <artifactId>spring-kafka-avro</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.avro</groupId>
                        <artifactId>avro-maven-plugin</artifactId>
                        <version>1.12.2</version>
                        <executions>
                          <execution>
                            <phase>generate-sources</phase>
                            <goals><goal>schema</goal></goals>
                            <configuration>
                              <sourceDirectory>${project.basedir}/src/main/resources/avro</sourceDirectory>
                              <outputDirectory>${project.basedir}/target/generated-sources/avro</outputDirectory>
                              <stringType>String</stringType>
                              <fieldVisibility>public</fieldVisibility>
                              <createSetters>false</createSetters>
                              <enableDecimalLogicalType>true</enableDecimalLogicalType>
                              <imports><import>${project.basedir}/src/main/resources/avro/Role.avsc</import></imports>
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
                              <sources><source>${project.basedir}/target/generated-sources/avro</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig avro = result.jkBuild().pluginConfig("avro").orElseThrow();
        assertThat(avro.values())
                .containsEntry("src", "src/main/resources/avro")
                .containsEntry("field-visibility", "PUBLIC")
                .containsEntry("setters", false)
                .containsEntry("decimal-logical-type", true)
                .doesNotContainKey("string-type")
                .doesNotContainKey("version");
        assertThat(result.jkBuild().build().extraSrc())
                .as("the preset's output is its own contribution")
                .isEmpty();
        assertThat(result.report().issues()).noneMatch(i -> i.severity() == ImportReport.Severity.ERROR);
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`[avro]`") && m.contains("`org.apache.avro:avro`"))
                .noneMatch(m -> m.contains("`<plugin>avro-maven-plugin</plugin>` was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[avro]").contains("setters = false");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("avro")).isPresent();
    }

    /** The Maven plugin's own default string type is CharSequence, which the preset does not share. */
    @Test
    void an_unset_string_type_is_written_as_mavens_default(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>events</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.avro</groupId>
                        <artifactId>avro-maven-plugin</artifactId>
                        <version>1.11.4</version>
                        <configuration>
                          <testSourceDirectory>src/test/avro</testSourceDirectory>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig avro = result.jkBuild().pluginConfig("avro").orElseThrow();
        assertThat(avro.values())
                .containsEntry("string-type", "CharSequence")
                .containsEntry("version", "1.11.4")
                .doesNotContainKey("src");
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`<testSourceDirectory>`") && m.contains("no `[avro]` key"));
    }

    /** The Baeldung jaxb module's shape: MojoHaus xjc over one schema directory with a package and bindings. */
    @Test
    void mojohaus_jaxb2_plugin_becomes_the_jaxb_preset(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.baeldung</groupId>
                  <artifactId>jaxb</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>jaxb2-maven-plugin</artifactId>
                        <version>3.2.0</version>
                        <executions>
                          <execution>
                            <id>xjc</id>
                            <goals><goal>xjc</goal></goals>
                          </execution>
                          <execution>
                            <id>schemagen</id>
                            <goals><goal>schemagen</goal></goals>
                          </execution>
                        </executions>
                        <configuration>
                          <sources><source>src/main/resources/global.xsd</source></sources>
                          <xjbSources><xjbSource>src/main/resources/global.xjb</xjbSource></xjbSources>
                          <outputDirectory>${basedir}/src/main/java</outputDirectory>
                          <packageName>com.baeldung.jaxb.gen</packageName>
                          <clearOutputDir>false</clearOutputDir>
                          <noPackageLevelAnnotations>true</noPackageLevelAnnotations>
                          <arguments><argument>-mark-generated</argument></arguments>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig jaxb = result.jkBuild().pluginConfig("jaxb").orElseThrow();
        assertThat(jaxb.values())
                .containsEntry("src", "src/main/resources/global.xsd")
                .containsEntry("package", "com.baeldung.jaxb.gen")
                .containsEntry("bindings", List.of("src/main/resources/global.xjb"))
                .containsEntry("arguments", List.of("-npa", "-mark-generated"));
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`[jaxb]`") && m.contains("jakarta.xml.bind-api"))
                .anyMatch(m -> m.contains("goal `schemagen`"))
                .noneMatch(m -> m.contains("`<plugin>jaxb2-maven-plugin</plugin>` was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[jaxb]").contains("package = \"com.baeldung.jaxb.gen\"");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("jaxb")).isPresent();
    }

    /** The jvnet plugin's keys have other names and other defaults; they land in the same table. */
    @Test
    void jvnet_maven_jaxb2_plugin_becomes_the_jaxb_preset(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>soap</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jvnet.jaxb2.maven2</groupId>
                        <artifactId>maven-jaxb2-plugin</artifactId>
                        <version>0.15.3</version>
                        <executions>
                          <execution><goals><goal>generate</goal></goals></execution>
                        </executions>
                        <configuration>
                          <schemaDirectory>${project.basedir}/src/main/resources/wsdl</schemaDirectory>
                          <generatePackage>com.acme.soap</generatePackage>
                          <generateDirectory>${project.build.directory}/generated-sources/xjc</generateDirectory>
                          <extension>true</extension>
                          <args><arg>-Xfluent-api</arg></args>
                          <plugins><plugin><groupId>net.java.dev.jaxb2-commons</groupId><artifactId>jaxb-fluent-api</artifactId></plugin></plugins>
                        </configuration>
                      </plugin>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.6.0</version>
                        <executions>
                          <execution>
                            <goals><goal>add-source</goal></goals>
                            <configuration>
                              <sources><source>${project.build.directory}/generated-sources/xjc</source></sources>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig jaxb = result.jkBuild().pluginConfig("jaxb").orElseThrow();
        assertThat(jaxb.values())
                .containsEntry("src", "src/main/resources/wsdl")
                .containsEntry("package", "com.acme.soap")
                .containsEntry("extension", true)
                .containsEntry("arguments", List.of("-Xfluent-api"))
                .doesNotContainKey("bindings");
        assertThat(result.jkBuild().build().extraSrc()).isEmpty();
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`<plugins>`") && m.contains("no `[jaxb]` key"))
                .noneMatch(m -> m.contains("`<plugin>maven-jaxb2-plugin</plugin>` was not imported"));
    }

    /** The Baeldung spring-jooq shape: a live database from POM properties, generating into the tree. */
    @Test
    void jooq_codegen_over_a_live_database_becomes_the_jooq_preset_with_jdbc_keys(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.baeldung</groupId>
                  <artifactId>spring-jooq</artifactId>
                  <version>1.0</version>
                  <properties>
                    <db.url>jdbc:h2:~/jooq</db.url>
                    <db.username>sa</db.username>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jooq</groupId>
                        <artifactId>jooq-codegen-maven</artifactId>
                        <version>3.19.24</version>
                        <executions>
                          <execution>
                            <phase>generate-sources</phase>
                            <goals><goal>generate</goal></goals>
                            <configuration>
                              <jdbc>
                                <url>${db.url}</url>
                                <user>${db.username}</user>
                                <password>${db.password}</password>
                              </jdbc>
                              <generator>
                                <target>
                                  <packageName>com.baeldung.jooq.introduction.db</packageName>
                                  <directory>src/main/java</directory>
                                </target>
                                <database>
                                  <inputSchema>PUBLIC</inputSchema>
                                  <excludes>flyway_schema_history</excludes>
                                </database>
                                <generate>
                                  <pojos>true</pojos>
                                  <fluentSetters>true</fluentSetters>
                                </generate>
                              </generator>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig jooq = result.jkBuild().pluginConfig("jooq").orElseThrow();
        assertThat(jooq.values())
                .containsEntry("package", "com.baeldung.jooq.introduction.db")
                .containsEntry("excludes", "flyway_schema_history")
                .containsEntry("pojos", true)
                .containsEntry("fluent-setters", true)
                .containsEntry("jdbc-url", "jdbc:h2:~/jooq")
                .containsEntry("jdbc-user", "sa")
                .containsEntry("version", "3.19.24")
                .doesNotContainKey("jdbc-password")
                .doesNotContainKey("schema");
        assertThat(messages(result))
                .anyMatch(m -> m.contains("live database") && m.contains("`sql`"))
                .anyMatch(m -> m.contains("`src/main/java`") && m.contains("delete the checked-in copies"))
                .noneMatch(m -> m.contains("`<plugin>jooq-codegen-maven</plugin>` was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[jooq]").contains("jdbc-url = \"jdbc:h2:~/jooq\"");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("jooq")).isPresent();
    }

    /** A DDLDatabase configuration: its scripts are the preset's own input. */
    @Test
    void jooq_codegen_over_ddl_scripts_names_them_as_sql(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>shop</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jooq</groupId>
                        <artifactId>jooq-codegen-maven</artifactId>
                        <version>3.21.8</version>
                        <configuration>
                          <generator>
                            <database>
                              <name>org.jooq.meta.extensions.ddl.DDLDatabase</name>
                              <properties>
                                <property><key>scripts</key><value>${basedir}/src/main/resources/db/*.sql</value></property>
                                <property><key>sort</key><value>semantic</value></property>
                                <property><key>defaultNameCase</key><value>lower</value></property>
                                <property><key>parseIgnoreComments</key><value>true</value></property>
                              </properties>
                              <inputSchema>shop</inputSchema>
                            </database>
                            <generate><records>false</records><daos>true</daos></generate>
                            <target>
                              <packageName>com.acme.jooq</packageName>
                              <directory>target/generated-sources/jooq</directory>
                            </target>
                          </generator>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig jooq = result.jkBuild().pluginConfig("jooq").orElseThrow();
        assertThat(jooq.values())
                .containsEntry("sql", "src/main/resources/db/*.sql")
                .containsEntry("name-case", "lower")
                .containsEntry("properties", Map.of("parseIgnoreComments", "true"))
                .containsEntry("schema", "shop")
                .containsEntry("records", false)
                .containsEntry("daos", true)
                .containsEntry("package", "com.acme.jooq")
                .doesNotContainKey("version")
                .doesNotContainKey("jdbc-url");
        assertThat(result.jkBuild().build().extraSrc()).isEmpty();
        assertThat(messages(result)).anyMatch(m -> m.contains("`[jooq]`") && m.contains("DDL scripts"));
    }
}
