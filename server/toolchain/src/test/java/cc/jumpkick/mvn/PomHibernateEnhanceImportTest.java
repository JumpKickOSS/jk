// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hibernate's build-time enhancement imports as {@code [hibernate] enhance = true}, from either
 * Maven plugin that runs it, with each switch written only when it differs from the plugins' default.
 */
class PomHibernateEnhanceImportTest {

    @Test
    void the_enhance_plugin_becomes_the_table_with_its_switches(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, pom("""
                <plugin>
                  <groupId>org.hibernate.orm.tooling</groupId>
                  <artifactId>hibernate-enhance-maven-plugin</artifactId>
                  <version>6.6.13.Final</version>
                  <executions>
                    <execution>
                      <goals><goal>enhance</goal></goals>
                      <configuration>
                        <enableLazyInitialization>true</enableLazyInitialization>
                        <enableDirtyTracking>false</enableDirtyTracking>
                        <enableAssociationManagement>true</enableAssociationManagement>
                      </configuration>
                    </execution>
                  </executions>
                </plugin>
                """));

        PluginConfig hibernate = result.jkBuild().pluginConfig("hibernate").orElseThrow();
        assertThat(hibernate.values())
                .isEqualTo(Map.of("enhance", true, "dirty-tracking", false, "association-management", true));
        assertThat(messages(result)).noneMatch(m -> m.contains("hibernate-enhance-maven-plugin` is not"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[hibernate]").contains("enhance = true");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("hibernate")).isPresent();
    }

    @Test
    void hibernate_maven_plugin_enhance_goal_is_the_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, pom("""
                <plugin>
                  <groupId>org.hibernate.orm</groupId>
                  <artifactId>hibernate-maven-plugin</artifactId>
                  <version>7.4.12.Final</version>
                  <executions>
                    <execution>
                      <goals><goal>enhance</goal></goals>
                    </execution>
                  </executions>
                </plugin>
                """));

        assertThat(result.jkBuild().pluginConfig("hibernate").orElseThrow().values())
                .isEqualTo(Map.of("enhance", true));
    }

    @Test
    void hibernate_maven_plugin_without_enhance_is_no_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, pom("""
                <plugin>
                  <groupId>org.hibernate.orm</groupId>
                  <artifactId>hibernate-maven-plugin</artifactId>
                  <version>7.4.12.Final</version>
                  <executions>
                    <execution>
                      <goals><goal>hbm2ddl</goal></goals>
                    </execution>
                  </executions>
                </plugin>
                """));

        assertThat(result.jkBuild().pluginConfig("hibernate")).isEmpty();
    }

    private static String pom(String plugin) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                """ + plugin + """
                    </plugins>
                  </build>
                </project>
                """;
    }
}
