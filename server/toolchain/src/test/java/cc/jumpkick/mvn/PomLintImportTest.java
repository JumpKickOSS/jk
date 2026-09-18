// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the lint plugins land: one {@code [lint]} table with each tool's configuration, the test
 * root added when a plugin lints the tests, and a row for what the table has no key for.
 */
class PomLintImportTest {

    /** TheAlgorithms-Java's shape: all three plugins, Checkstyle at warning severity over the tests too. */
    @Test
    void the_three_lint_plugins_become_one_lint_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.thealgorithms</groupId>
                  <artifactId>Java</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <version>3.6.0</version>
                        <configuration>
                          <configLocation>checkstyle.xml</configLocation>
                          <consoleOutput>true</consoleOutput>
                          <includeTestSourceDirectory>true</includeTestSourceDirectory>
                          <violationSeverity>warning</violationSeverity>
                        </configuration>
                        <dependencies>
                          <dependency>
                            <groupId>com.puppycrawl.tools</groupId>
                            <artifactId>checkstyle</artifactId>
                            <version>10.21.0</version>
                          </dependency>
                        </dependencies>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-pmd-plugin</artifactId>
                        <version>3.28.0</version>
                        <configuration>
                          <rulesets>
                            <ruleset>/rulesets/java/maven-pmd-plugin-default.xml</ruleset>
                            <ruleset>/category/java/security.xml</ruleset>
                            <ruleset>file://${basedir}/pmd-custom_ruleset.xml</ruleset>
                          </rulesets>
                          <printFailingErrors>true</printFailingErrors>
                          <includeTests>true</includeTests>
                          <excludeFromFailureFile>pmd-exclude.properties</excludeFromFailureFile>
                        </configuration>
                      </plugin>
                      <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <version>4.9.3.0</version>
                        <configuration>
                          <excludeFilterFile>spotbugs-exclude.xml</excludeFilterFile>
                          <includeTests>true</includeTests>
                          <effort>Max</effort>
                          <plugins>
                            <plugin>
                              <groupId>com.mebigfatguy.fb-contrib</groupId>
                              <artifactId>fb-contrib</artifactId>
                              <version>7.7.4</version>
                            </plugin>
                          </plugins>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .containsEntry("checkstyle", "checkstyle.xml")
                .containsEntry("checkstyle-version", "10.21.0")
                .containsEntry("fail-on", "warning")
                .containsEntry(
                        "pmd",
                        List.of("rulesets/java/quickstart.xml", "category/java/security.xml", "pmd-custom_ruleset.xml"))
                .containsEntry("spotbugs", true)
                .containsEntry("spotbugs-exclude", "spotbugs-exclude.xml")
                .containsEntry("spotbugs-effort", "max")
                .containsEntry("spotbugs-version", "4.9.3")
                .containsEntry("sources", List.of("src/main/java", "src/test/java"));
        List<String> rows = messages(result);
        assertThat(rows)
                .anyMatch(m -> m.contains("Maven plugin's own default ruleset"))
                .anyMatch(m -> m.contains("`<excludeFromFailureFile>`"))
                .anyMatch(m -> m.contains("`<plugins>`") && m.contains("fb-contrib"))
                .anyMatch(m -> m.contains("`[lint]`") && m.contains("jk-results.md"))
                .noneMatch(m -> m.contains("was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[lint]").contains("spotbugs = true");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("lint")).isPresent();
    }

    /** A Checkstyle plugin left at its defaults reads a rule set the module does not hold. */
    @Test
    void a_built_in_checkstyle_rule_set_is_a_row_and_a_placeholder_path(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <version>3.6.0</version>
                        <configuration><configLocation>google_checks.xml</configLocation></configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .containsEntry("checkstyle", "config/checkstyle.xml")
                .doesNotContainKey("sources")
                .doesNotContainKey("fail-on")
                .doesNotContainKey("checkstyle-version");
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`google_checks.xml`") && m.contains("copy the rule set in"));
    }

    @Test
    void a_pom_without_lint_plugins_writes_no_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("lint")).isEmpty();
    }
}
