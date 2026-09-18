// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the lint plugins land: one {@code [lint]} table with each tool's configuration, the test
 * root added when a plugin lints the tests, and a row for what the table has no key for.
 */
class PomLintImportTest {

    /**
     * nacos's shape: the root POM's checkstyle configuration and SpotBugs filter sit under the root's
     * {@code style/}, and every module inherits the plugins; a module's table names them by the path
     * from the module, so the step finds them where they are.
     */
    @Test
    void an_inherited_configuration_file_is_named_by_its_path_from_the_module(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>api</module></modules>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <configuration>
                          <configLocation>style/checks.xml</configLocation>
                          <excludes>**/api/grpc/auto/**,**/istio/**
                          </excludes>
                        </configuration>
                        <executions><execution><goals><goal>check</goal></goals></execution></executions>
                      </plugin>
                      <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <configuration><excludeFilterFile>style/spotbugs-exclude.xml</excludeFilterFile></configuration>
                        <executions><execution><goals><goal>check</goal></goals></execution></executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        Files.createDirectories(root.resolve("style"));
        Files.writeString(root.resolve("style/checks.xml"), "<module name=\"Checker\"/>");
        Files.writeString(root.resolve("style/spotbugs-exclude.xml"), "<FindBugsFilter/>");
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>api</artifactId>
                </project>
                """);

        PomImporter.Result result = TestImporters.offline(tempDir).importFrom(api.resolve("pom.xml"));

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .containsEntry("checkstyle", "../style/checks.xml")
                .containsEntry("exclude", List.of("**/api/grpc/auto/**", "**/istio/**"))
                .containsEntry("spotbugs-exclude", "../style/spotbugs-exclude.xml");
    }

    /** spring-cloud-alibaba's shape: the rule set is a URL, which no module-relative path can name. */
    @Test
    void a_checkstyle_rule_set_at_a_url_is_a_row_and_the_key_names_the_url(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <configuration><configLocation>https://example.com/build-tools/nohttp-checkstyle.xml</configLocation></configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .as("the key says what the POM said, so the step's warning names the rule set")
                .containsEntry("checkstyle", "https://example.com/build-tools/nohttp-checkstyle.xml");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("https://example.com/build-tools/nohttp-checkstyle.xml")
                .contains("copy the rule set in"));
    }

    /** jenkins's shape: the rule set is named through a property only the Maven launcher sets. */
    @Test
    void a_checkstyle_rule_set_named_through_a_launcher_property_is_a_row_and_the_key_keeps_the_spelling(
            @TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jenkins-ci.main</groupId>
                  <artifactId>websocket-spi</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <configuration>
                          <configLocation>${maven.multiModuleProjectDirectory}/src/checkstyle/checkstyle-configuration.xml</configLocation>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .containsEntry(
                        "checkstyle",
                        "${maven.multiModuleProjectDirectory}/src/checkstyle/checkstyle-configuration.xml");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("`${maven.multiModuleProjectDirectory}/src/checkstyle/checkstyle-configuration.xml`")
                .contains("a property no POM defines")
                .contains("copy the rule set in"));
    }

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
                        List.of(
                                "rulesets/java/maven-pmd-plugin-default.xml",
                                "category/java/security.xml",
                                "pmd-custom_ruleset.xml"))
                .containsEntry("pmd-exclude", "pmd-exclude.properties")
                .containsEntry("pmd-version", "7.17.0")
                .containsEntry("spotbugs", true)
                .containsEntry("spotbugs-exclude", "spotbugs-exclude.xml")
                .containsEntry("spotbugs-effort", "max")
                .containsEntry("spotbugs-version", "4.9.3")
                .containsEntry("sources", List.of("src/main/java", "src/test/java"));
        List<String> rows = messages(result);
        assertThat(rows)
                .noneMatch(m -> m.contains("default ruleset"))
                .noneMatch(m -> m.contains("`<excludeFromFailureFile>`"))
                .noneMatch(m -> m.contains("has one threshold"))
                .anyMatch(m -> m.contains("`<plugins>`") && m.contains("fb-contrib"))
                .anyMatch(m -> m.contains("`[lint]`") && m.contains("jk-results.md"))
                .noneMatch(m -> m.contains("was not imported"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[lint]").contains("spotbugs = true");
        assertThat(JkBuildParser.parse(rendered).pluginConfig("lint")).isPresent();
    }

    /** A Checkstyle plugin left at its defaults reads a rule set the module does not hold. */
    @Test
    void a_built_in_checkstyle_rule_set_is_a_row_and_the_key_names_it(@TempDir Path tempDir) throws Exception {
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
                .containsEntry("checkstyle", "google_checks.xml")
                .doesNotContainKey("sources")
                .doesNotContainKey("fail-on")
                .doesNotContainKey("checkstyle-version");
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`google_checks.xml`") && m.contains("copy the rule set in"));
    }

    /** Maven fails on every PMD priority up to failurePriority (default 5): fail-on follows it, not Checkstyle's severity. */
    @Test
    void pmds_failure_threshold_sets_fail_on(@TempDir Path tempDir) throws Exception {
        PluginConfig defaults = pmdOnly(tempDir.resolve("defaults"), "");
        assertThat(defaults.values()).containsEntry("fail-on", "warning");

        PluginConfig priorityTwo = pmdOnly(tempDir.resolve("two"), "<failurePriority>2</failurePriority>");
        assertThat(priorityTwo.values())
                .as("priorities 1 and 2 are jk's errors, the table's default threshold")
                .doesNotContainKey("fail-on");

        PluginConfig noFailure = pmdOnly(tempDir.resolve("never"), "<failOnViolation>false</failOnViolation>");
        assertThat(noFailure.values()).containsEntry("fail-on", "never");
    }

    /** Checkstyle fails on errors alone under Maven while PMD fails on every finding: one key, the stricter, and a row. */
    @Test
    void tools_that_disagree_on_the_threshold_get_the_stricter_one_and_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <configuration><configLocation>checkstyle.xml</configLocation></configuration>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-pmd-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values()).containsEntry("fail-on", "warning");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("Checkstyle fails the build on `error`")
                .contains("PMD fails the build on `warning`")
                .contains("`fail-on = \"warning\"`"));
    }

    /** The PMD a POM runs is the plugin's bundled one, or the pmd-java the plugin's own dependencies pin. */
    @Test
    void the_pmd_release_follows_the_plugin_or_its_pinned_pmd_java(@TempDir Path tempDir) throws Exception {
        PluginConfig pinned = pmdOnly(tempDir.resolve("pinned"), "3.26.0", "", """
                <dependencies>
                  <dependency>
                    <groupId>net.sourceforge.pmd</groupId><artifactId>pmd-java</artifactId><version>7.20.0</version>
                  </dependency>
                </dependencies>
                """);
        assertThat(pinned.values()).containsEntry("pmd-version", "7.20.0");

        PluginConfig bundled = pmdOnly(tempDir.resolve("bundled"), "3.26.0", "", "");
        assertThat(bundled.values()).containsEntry("pmd-version", "7.7.0");

        PomImporter.Result six = importPmdOnly(tempDir.resolve("six"), "3.21.2", "", "");
        assertThat(six.jkBuild().pluginConfig("lint").orElseThrow().values()).doesNotContainKey("pmd-version");
        assertThat(messages(six))
                .anySatisfy(m -> assertThat(m).contains("PMD 6.55.0").contains("PMD 7"));
    }

    /** spotbugs-maven-plugin reports at medium confidence unless {@code <threshold>} says otherwise. */
    @Test
    void spotbugs_threshold_is_the_tables_confidence(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <version>4.10.4.1</version>
                        <configuration><threshold>Low</threshold></configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values()).containsEntry("spotbugs-threshold", "low").doesNotContainKey("spotbugs-version");
    }

    private static PluginConfig pmdOnly(Path dir, String configuration) throws Exception {
        return pmdOnly(dir, "3.28.0", configuration, "");
    }

    private static PluginConfig pmdOnly(Path dir, String version, String configuration, String dependencies)
            throws Exception {
        return importPmdOnly(dir, version, configuration, dependencies)
                .jkBuild()
                .pluginConfig("lint")
                .orElseThrow();
    }

    private static PomImporter.Result importPmdOnly(Path dir, String version, String configuration, String dependencies)
            throws Exception {
        return TestImporters.importXml(dir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-pmd-plugin</artifactId>
                        <version>%s</version>
                        <configuration>%s</configuration>
                        %s
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(version, configuration, dependencies));
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

    /** The migration plugins are rows naming the tool recipe, with the POM's URL and change log in them. */
    @Test
    void the_migration_plugins_are_rows_naming_the_tool_recipe(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.flywaydb</groupId>
                        <artifactId>flyway-maven-plugin</artifactId>
                        <version>11.13.2</version>
                        <configuration>
                          <url>jdbc:h2:file:./target/db</url>
                          <user>sa</user>
                          <locations><location>filesystem:src/main/resources/db/migration</location></locations>
                        </configuration>
                      </plugin>
                      <plugin>
                        <groupId>org.liquibase</groupId>
                        <artifactId>liquibase-maven-plugin</artifactId>
                        <version>4.33.0</version>
                        <configuration>
                          <changeLogFile>config/liquibase/master.xml</changeLogFile>
                          <url>${liquibase-plugin.url}</url>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfigs()).isEmpty();
        List<String> rows = messages(result);
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("`flyway-maven-plugin`")
                .contains("flyway-commandline:<version> --main org.flywaydb.commandline.Main --with")
                .contains("`FLYWAY_URL` (`jdbc:h2:file:./target/db`)")
                .contains("database.md"));
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("`liquibase-maven-plugin`")
                .contains("LiquibaseCommandLine")
                .contains("`LIQUIBASE_COMMAND_URL` in")
                .contains("LIQUIBASE_COMMAND_CHANGELOG_FILE=config/liquibase/master.xml"));
        assertThat(rows).noneMatch(m -> m.contains("was not imported"));
    }

    /** nacos's shape: the root configures SpotBugs for whoever runs {@code mvn spotbugs:check}; no module gets a table. */
    @Test
    void an_inherited_lint_plugin_with_no_executions_is_one_row_and_no_tables(@TempDir Path root) throws Exception {
        writeReactor(root, """
                <plugin>
                  <groupId>com.github.spotbugs</groupId>
                  <artifactId>spotbugs-maven-plugin</artifactId>
                  <version>4.10.4.1</version>
                  <configuration><effort>Max</effort></configuration>
                </plugin>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.modules()).hasSize(2);
        assertThat(result.modules().values())
                .allSatisfy(module -> assertThat(module.pluginConfig("lint")).isEmpty());
        assertThat(result.report().issues().stream()
                        .map(i -> i.message())
                        .filter(m -> m.contains("spotbugs-maven-plugin")))
                .singleElement()
                .satisfies(m -> assertThat(m)
                        .startsWith("`spotbugs-maven-plugin` binds no `<execution>`, so Maven runs it only by hand"
                                + " (`mvn spotbugs:check`); no `[lint] spotbugs` was written")
                        .endsWith("Declared by the root pom.xml, inherited by 2 modules."));
    }

    /** A parent's execution bound to a phase runs on every module under Maven, so every module gets the table. */
    @Test
    void an_inherited_lint_plugin_with_an_execution_is_a_table_on_every_module(@TempDir Path root) throws Exception {
        writeReactor(root, """
                <plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-pmd-plugin</artifactId>
                  <version>3.26.0</version>
                  <executions>
                    <execution>
                      <phase>compile</phase>
                      <goals><goal>check</goal></goals>
                    </execution>
                  </executions>
                </plugin>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.modules().values()).allSatisfy(module -> assertThat(
                        module.pluginConfig("lint").orElseThrow().stringList("pmd"))
                .containsExactly("rulesets/java/maven-pmd-plugin-default.xml"));
        assertThat(result.report().issues()).noneMatch(i -> i.message().contains("binds no `<execution>`"));
    }

    /** A ruleset spelled through a property nothing defines (a directory plugin's, set at run time) is a row, not silence. */
    @Test
    void a_ruleset_named_through_an_undefined_property_is_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.baeldung</groupId>
                  <artifactId>parent-modules</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-pmd-plugin</artifactId>
                        <version>3.26.0</version>
                        <configuration>
                          <rulesets>
                            <ruleset>${tutorialsproject.basedir}/baeldung-pmd-rules.xml</ruleset>
                          </rulesets>
                        </configuration>
                        <executions>
                          <execution>
                            <phase>compile</phase>
                            <goals><goal>check</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.stringList("pmd")).containsExactly("rulesets/java/quickstart.xml");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("names the ruleset `${tutorialsproject.basedir}/baeldung-pmd-rules.xml` through a property"
                        + " no POM defines")
                .contains("`rulesets/java/quickstart.xml` stands in"));
    }

    /** A root declaring {@code plugin} under {@code <build><plugins>} over two leaf modules. */
    private static void writeReactor(Path root, String plugin) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                  <build>
                    <plugins>
                      %s
                    </plugins>
                  </build>
                </project>
                """.formatted(plugin));
        for (String module : List.of("core", "app")) {
            Path dir = Files.createDirectories(root.resolve(module));
            Files.writeString(dir.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>org.demo</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <artifactId>%s</artifactId>
                    </project>
                    """.formatted(module));
        }
    }
}
