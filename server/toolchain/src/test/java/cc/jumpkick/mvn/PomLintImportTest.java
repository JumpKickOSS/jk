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
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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

    /** spring-cloud-alibaba's shape: the rule set is a URL, which the step fetches and runs as it is. */
    @Test
    void a_checkstyle_rule_set_at_a_url_is_the_key_and_no_row(@TempDir Path tempDir) throws Exception {
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
        assertThat(lint.values()).containsEntry("checkstyle", "https://example.com/build-tools/nohttp-checkstyle.xml");
        assertThat(messages(result)).noneMatch(m -> m.contains("copy the rule set in"));
    }

    /**
     * jenkins's shape: the rule set and its suppressions are named through {@code
     * ${maven.multiModuleProjectDirectory}}, the launcher property that is the reactor root — so
     * the module's keys are the paths from the module to the root's files, whether the module is
     * imported with its workspace or on its own.
     */
    @Test
    void the_launcher_property_for_the_reactor_root_resolves_to_the_root(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.jenkins-ci.main</groupId>
                  <artifactId>jenkins-parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>websocket/spi</module></modules>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <configuration>
                          <configLocation>${maven.multiModuleProjectDirectory}/src/checkstyle/checkstyle-configuration.xml</configLocation>
                          <suppressionsLocation>${maven.multiModuleProjectDirectory}/src/checkstyle/checkstyle-suppressions.xml</suppressionsLocation>
                        </configuration>
                        <executions><execution><goals><goal>check</goal></goals></execution></executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        Files.createDirectories(root.resolve("src/checkstyle"));
        Files.writeString(root.resolve("src/checkstyle/checkstyle-configuration.xml"), "<module name=\"Checker\"/>");
        Files.writeString(root.resolve("src/checkstyle/checkstyle-suppressions.xml"), "<suppressions/>");
        Path spi = Files.createDirectories(root.resolve("websocket/spi"));
        Files.writeString(spi.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.jenkins-ci.main</groupId>
                    <artifactId>jenkins-parent</artifactId>
                    <version>1.0</version>
                    <relativePath>../../pom.xml</relativePath>
                  </parent>
                  <artifactId>websocket-spi</artifactId>
                </project>
                """);

        PomImporter.WorkspaceImportResult workspace =
                TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        PluginConfig member = Objects.requireNonNull(workspace.modules().get("websocket/spi"))
                .pluginConfig("lint")
                .orElseThrow();
        assertThat(member.values())
                .containsEntry("checkstyle", "../../src/checkstyle/checkstyle-configuration.xml")
                .containsEntry("checkstyle-suppressions", "../../src/checkstyle/checkstyle-suppressions.xml");
        assertThat(workspace.report().issues())
                .noneMatch(i -> i.message().contains("a property no POM defines"))
                .noneMatch(i -> i.message().contains("copy the rule set in"));

        PomImporter.Result alone = TestImporters.offline(root.resolve("alone")).importFrom(spi.resolve("pom.xml"));
        assertThat(alone.jkBuild().pluginConfig("lint").orElseThrow().values())
                .as("imported on its own, the module still finds the reactor root above it")
                .containsEntry("checkstyle", "../../src/checkstyle/checkstyle-configuration.xml")
                .containsEntry("checkstyle-suppressions", "../../src/checkstyle/checkstyle-suppressions.xml");
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
                .as("offline, the plugin's POM is unread and the PMD release is left to the step")
                .doesNotContainKey("pmd-version")
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

    /**
     * spring-cloud-build's shape: the plugin's own {@code <dependencies>} carry the rule set, a
     * header and the checks it names as classpath resources, one execution reads {@code
     * checkstyle.xml} from them with {@code <headerLocation>} and {@code <propertyExpansion>}, and
     * a second execution runs the nohttp rule set from a URL over every file. Each execution is one
     * Checkstyle run: the first the table's own keys, the second a {@code [lint.<execution-id>]}
     * entry; the plugin's dependencies are {@code checkstyle-classpath}; {@code
     * checkstyle.suppressions.file} in the expansion is {@code checkstyle-suppressions}, the header
     * {@code checkstyle-header}, and the rest of the expansion {@code checkstyle-properties}.
     */
    @Test
    void two_checkstyle_executions_are_two_runs_and_a_classpath_rule_set_has_its_keys(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, SPRING_CLOUD_BUILD.formatted("false"));

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .containsEntry("checkstyle", "checkstyle.xml")
                .containsEntry(
                        "checkstyle-classpath",
                        List.of(
                                "io.spring.javaformat:spring-javaformat-checkstyle:0.0.47",
                                "org.springframework.cloud:spring-cloud-build-tools:5.0.3",
                                "io.spring.nohttp:nohttp-checkstyle:0.0.11"))
                .containsEntry("checkstyle-version", "12.1.2")
                .containsEntry("checkstyle-header", "checkstyle-header.txt")
                .containsEntry(
                        "checkstyle-suppressions",
                        "https://raw.githubusercontent.com/spring-cloud/spring-cloud-build/master/checkstyle-suppressions.xml")
                .containsEntry(
                        "checkstyle-properties",
                        Map.of(
                                "checkstyle.build.directory",
                                "target",
                                "checkstyle.additional.suppressions.file",
                                "src/checkstyle/checkstyle-suppressions.xml",
                                "checkstyle.header.check",
                                "src/checkstyle/header-check.txt"))
                .containsEntry("sources", List.of("src/main/java", "src/test/java"));
        assertThat(lint.entries()).containsOnlyKeys("no-http-checkstyle-validation");
        assertThat(lint.entries().get("no-http-checkstyle-validation"))
                .containsEntry(
                        "checkstyle",
                        "https://raw.githubusercontent.com/spring-cloud/spring-cloud-build/master/nohttp-checkstyle.xml")
                .containsEntry("sources", List.of("."))
                .containsEntry("exclude", List.of("**/.idea/**/*", "**/.git/**/*", "**/target/**/*", "**/*.log"))
                .containsEntry(
                        "checkstyle-classpath",
                        List.of(
                                "io.spring.javaformat:spring-javaformat-checkstyle:0.0.47",
                                "org.springframework.cloud:spring-cloud-build-tools:5.0.3",
                                "io.spring.nohttp:nohttp-checkstyle:0.0.11"))
                .doesNotContainKey("checkstyle-header")
                .doesNotContainKey("checkstyle-properties");
        assertThat(messages(result)).noneMatch(m -> m.contains("copy the rule set in"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[lint.no-http-checkstyle-validation]");
        PluginConfig reparsed =
                JkBuildParser.parse(rendered).pluginConfig("lint").orElseThrow();
        assertThat(reparsed.entries()).containsOnlyKeys("no-http-checkstyle-validation");
        assertThat(reparsed.stringMap("checkstyle-properties")).containsEntry("checkstyle.build.directory", "target");
    }

    /** An execution Maven skips ({@code <skip>true</skip>}) is no run: a row says so, and the table carries the other. */
    @Test
    void a_skipped_checkstyle_execution_is_a_row_not_a_run(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, SPRING_CLOUD_BUILD.formatted("true"));

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values()).containsEntry("checkstyle", "checkstyle.xml");
        assertThat(lint.entries()).isEmpty();
        assertThat(messages(result))
                .anyMatch(m -> m.contains("`no-http-checkstyle-validation`") && m.contains("skipped"));
    }

    private static final String SPRING_CLOUD_BUILD = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.springframework.cloud</groupId>
              <artifactId>spring-cloud-build</artifactId>
              <version>5.0.3</version>
              <properties>
                <disable.nohttp.checks>%s</disable.nohttp.checks>
                <checkstyle.suppressions.file>https://raw.githubusercontent.com/spring-cloud/spring-cloud-build/master/checkstyle-suppressions.xml</checkstyle.suppressions.file>
                <checkstyle.additional.suppressions.file>${project.basedir}/src/checkstyle/checkstyle-suppressions.xml</checkstyle.additional.suppressions.file>
              </properties>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-checkstyle-plugin</artifactId>
                    <version>3.6.0</version>
                    <dependencies>
                      <dependency>
                        <groupId>com.puppycrawl.tools</groupId>
                        <artifactId>checkstyle</artifactId>
                        <version>12.1.2</version>
                      </dependency>
                      <dependency>
                        <groupId>io.spring.javaformat</groupId>
                        <artifactId>spring-javaformat-checkstyle</artifactId>
                        <version>0.0.47</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.cloud</groupId>
                        <artifactId>spring-cloud-build-tools</artifactId>
                        <version>5.0.3</version>
                      </dependency>
                      <dependency>
                        <groupId>io.spring.nohttp</groupId>
                        <artifactId>nohttp-checkstyle</artifactId>
                        <version>0.0.11</version>
                      </dependency>
                    </dependencies>
                    <executions>
                      <execution>
                        <id>checkstyle-validation</id>
                        <phase>validate</phase>
                        <configuration>
                          <configLocation>checkstyle.xml</configLocation>
                          <headerLocation>checkstyle-header.txt</headerLocation>
                          <propertyExpansion>
                            checkstyle.build.directory=${project.build.directory}
                            checkstyle.suppressions.file=${checkstyle.suppressions.file}
                            checkstyle.additional.suppressions.file=${checkstyle.additional.suppressions.file}
                            checkstyle.header.check=${maven.multiModuleProjectDirectory}/src/checkstyle/header-check.txt
                          </propertyExpansion>
                          <consoleOutput>true</consoleOutput>
                          <includeTestSourceDirectory>true</includeTestSourceDirectory>
                        </configuration>
                        <goals><goal>check</goal></goals>
                      </execution>
                      <execution>
                        <id>no-http-checkstyle-validation</id>
                        <phase>validate</phase>
                        <configuration>
                          <skip>${disable.nohttp.checks}</skip>
                          <configLocation>https://raw.githubusercontent.com/spring-cloud/spring-cloud-build/master/nohttp-checkstyle.xml</configLocation>
                          <includes>**/*</includes>
                          <excludes>**/.idea/**/*,**/.git/**/*,**/target/**/*,**/*.log</excludes>
                          <sourceDirectories>./</sourceDirectories>
                        </configuration>
                        <goals><goal>check</goal></goals>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

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

    /** Checkstyle fails on errors alone under Maven while PMD fails on every finding: each tool keeps its own threshold. */
    @Test
    void tools_that_disagree_on_the_threshold_each_get_their_own_key_and_no_row(@TempDir Path tempDir)
            throws Exception {
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
                      <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <configuration><failOnError>false</failOnError></configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PluginConfig lint = result.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .as("Checkstyle's error-only threshold is the default and needs no key")
                .doesNotContainKey("fail-on")
                .doesNotContainKey("checkstyle-fail-on")
                .containsEntry("pmd-fail-on", "warning")
                .containsEntry("spotbugs-fail-on", "never");
        assertThat(messages(result)).noneMatch(m -> m.contains("has one threshold"));
    }

    /**
     * The PMD a POM runs is the pmd-java the plugin's own dependencies pin, else the one the plugin
     * release bundles — its POM's {@code pmdVersion}, read from the repository at import time.
     */
    @Test
    void the_pmd_release_follows_the_plugin_or_its_pinned_pmd_java(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("repo");
        servePmdPlugin(repo, "3.26.0", "7.7.0");
        servePmdPlugin(repo, "3.21.2", "6.55.0");

        PluginConfig pinned = pmdOnly(tempDir.resolve("pinned"), repo, "3.26.0", "", """
                <dependencies>
                  <dependency>
                    <groupId>net.sourceforge.pmd</groupId><artifactId>pmd-java</artifactId><version>7.20.0</version>
                  </dependency>
                </dependencies>
                """);
        assertThat(pinned.values()).containsEntry("pmd-version", "7.20.0");

        PluginConfig bundled = pmdOnly(tempDir.resolve("bundled"), repo, "3.26.0", "", "");
        assertThat(bundled.values()).containsEntry("pmd-version", "7.7.0");

        PomImporter.Result six = importPmdOnly(tempDir.resolve("six"), repo, "3.21.2", "", "");
        assertThat(six.jkBuild().pluginConfig("lint").orElseThrow().values()).doesNotContainKey("pmd-version");
        assertThat(messages(six))
                .anySatisfy(m -> assertThat(m).contains("PMD 6.55.0").contains("PMD 7"));

        PomImporter.Result unserved = importPmdOnly(tempDir.resolve("unserved"), repo, "3.99.0", "", "");
        assertThat(unserved.jkBuild().pluginConfig("lint").orElseThrow().values())
                .as("a release whose POM no repository serves pins nothing")
                .doesNotContainKey("pmd-version");
        assertThat(messages(unserved)).anySatisfy(m -> assertThat(m)
                .contains("`maven-pmd-plugin` 3.99.0")
                .contains("POM")
                .contains("`pmd-version`"));
    }

    /** A {@code maven-pmd-plugin} release's POM under {@code repo}, naming the PMD it bundles the way the real ones do. */
    static void servePmdPlugin(Path repo, String version, String pmdVersion) throws Exception {
        Path pom = repo.resolve(TestImporters.pomPath("org.apache.maven.plugins", "maven-pmd-plugin", version)
                .substring(1));
        Files.createDirectories(Objects.requireNonNull(pom.getParent()));
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-pmd-plugin</artifactId>
                  <version>%s</version>
                  <packaging>maven-plugin</packaging>
                  <properties>
                    <pmdVersion>%s</pmdVersion>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>net.sourceforge.pmd</groupId>
                      <artifactId>pmd-java</artifactId>
                      <version>${pmdVersion}</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, pmdVersion));
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
        assertThat(lint.values())
                .containsEntry("spotbugs-threshold", "low")
                .as("spotbugs:check fails on any bug at the confidence threshold")
                .containsEntry("fail-on", "warning")
                .doesNotContainKey("spotbugs-version");
    }

    private static PluginConfig pmdOnly(Path dir, String configuration) throws Exception {
        return pmdOnly(dir, null, "3.28.0", configuration, "");
    }

    private static PluginConfig pmdOnly(
            Path dir, @Nullable Path repo, String version, String configuration, String dependencies) throws Exception {
        return importPmdOnly(dir, repo, version, configuration, dependencies)
                .jkBuild()
                .pluginConfig("lint")
                .orElseThrow();
    }

    /** A POM with the PMD plugin alone, imported over {@code repo} (a directory), or offline when null. */
    private static PomImporter.Result importPmdOnly(
            Path dir, @Nullable Path repo, String version, String configuration, String dependencies) throws Exception {
        String xml = """
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
                """.formatted(version, configuration, dependencies);
        if (repo == null) return TestImporters.importXml(dir, xml);
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), xml);
        return TestImporters.over(dir, repo.toUri()).importFrom(project.resolve("pom.xml"));
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
