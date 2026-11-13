// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where Surefire, Failsafe, JaCoCo and the compiler's test source globs land: groups in {@code
 * [test]} tag filters, excludes in {@code exclude-classes} / {@code exclude-src}, everything jk has
 * no key for in a row that names the setting and its landing place.
 */
class PomTestPluginImportTest {

    @Test
    void surefire_groups_become_tag_filters_and_the_rest_is_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "test-plugins-pom.xml");
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        assertThat(build.build().testIncludeTags()).containsExactly("fast", "smoke");
        assertThat(build.build().testExcludeTags()).containsExactly("slow");

        assertThat(build.build().testJvm().jvmArgs())
                .as("the argLine minus the ${argLine} placeholder and the JaCoCo agent")
                .containsExactly("-Xmx1g", "-Dfile.encoding=UTF-8");
        assertThat(build.build().testJvm().systemProperties())
                .containsExactly(Map.entry("spring.profiles.active", "test"), Map.entry("java.awt.headless", "true"));
        assertThat(messages).noneMatch(m -> m.startsWith("maven-surefire-plugin <argLine>"));
        assertThat(messages).noneMatch(m -> m.startsWith("maven-surefire-plugin system properties"));
        assertThat(build.build().testExcludeClasses())
                .as("the file pattern becomes the class pattern Maven's runner skips")
                .containsExactly("*Slow*");
        assertThat(messages).noneMatch(m -> m.startsWith("maven-surefire-plugin <excludes>"));
        assertThat(build.build().testSuites())
                .as("failsafe's default patterns are the integration suite's classes")
                .containsExactly(Map.entry("integration", DeclaredSuite.ofClasses(List.of("IT*", "*IT", "*ITCase"))));
        assertThat(messages).noneMatch(m -> m.startsWith("maven-failsafe-plugin runs"));
        assertThat(messages).noneMatch(m -> m.contains("src/integration/java"));
        assertThat(messages)
                .as("failsafe's argLine differs from surefire's, and [test] jvm-args is one list")
                .anyMatch(m -> m.startsWith("maven-failsafe-plugin <argLine> -Xmx2g:")
                        && m.contains("merge it into [test] jvm-args"));
        assertThat(messages).anyMatch(m -> m.startsWith("jacoco-maven-plugin:") && m.contains("jk test -T"));
        assertThat(messages).noneMatch(m -> m.contains("skips tests"));
        assertThat(messages)
                .as("surefire, failsafe and jacoco each have their own rows, never the generic one")
                .noneMatch(m -> m.matches("[\\w.-]+ not imported(; declared by .*)?"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[test]\ninclude-tags = [\"fast\", \"smoke\"]\nexclude-tags = [\"slow\"]\n"
                        + "exclude-classes = [\"*Slow*\"]\n"
                        + "jvm-args = [\"-Xmx1g\", \"-Dfile.encoding=UTF-8\"]\n"
                        + "system-properties = { \"spring.profiles.active\" = \"test\", \"java.awt.headless\" = \"true\" }\n"
                        + "\n[test.suites.integration]\nclasses = [\"IT*\", \"*IT\", \"*ITCase\"]\n");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.build().testIncludeTags()).containsExactly("fast", "smoke");
        assertThat(reparsed.build().testExcludeTags()).containsExactly("slow");
        assertThat(reparsed.build().testExcludeClasses()).containsExactly("*Slow*");
        assertThat(reparsed.build().testJvm()).isEqualTo(build.build().testJvm());
        assertThat(reparsed.build().testSuites()).isEqualTo(build.build().testSuites());
        assertThat(JkBuildParser.parseTestTags(writeManifest(tempDir, rendered)).excludeTags())
                .as("the engine's root-scoped reader sees the same filters")
                .containsExactly("slow");
    }

    /**
     * The jar lifecycle runs surefire and the jar plugin whether or not {@code <plugins>} lists
     * them, configured from {@code <pluginManagement>}; a parent that configures them there alone
     * still shapes the build.
     */
    @Test
    void plugin_management_alone_configures_the_lifecycle_bound_surefire_and_jar_plugins(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><pluginManagement><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <version>3.5.2</version>
                      <configuration>
                        <argLine>-Xmx1g</argLine>
                        <excludedGroups>slow</excludedGroups>
                      </configuration>
                    </plugin>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-jar-plugin</artifactId>
                      <version>3.4.2</version>
                      <configuration>
                        <archive><manifest><mainClass>com.ex.Main</mainClass></manifest></archive>
                      </configuration>
                    </plugin>
                  </plugins></pluginManagement></build>
                </project>
                """);
        JkBuild build = result.jkBuild();
        assertThat(build.build().testJvm().jvmArgs()).containsExactly("-Xmx1g");
        assertThat(build.build().testExcludeTags()).containsExactly("slow");
        assertThat(requireNonNull(build.application()).main()).isEqualTo("com.ex.Main");
    }

    @Test
    void a_tag_expression_and_a_skip_flag_are_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties><skipTests>true</skipTests></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration><groups>fast &amp; !flaky</groups></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        List<String> messages = TestImporters.messages(result);
        assertThat(result.jkBuild().build().testIncludeTags()).isEmpty();
        assertThat(messages)
                .anyMatch(
                        m -> m.startsWith("maven-surefire-plugin <groups> fast & !flaky: tag expression not imported"));
        assertThat(messages).anyMatch(m -> m.contains("skips tests") && m.contains("jk build -S"));
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("[test]");
    }

    @Test
    void the_argline_property_counts_when_surefire_declares_none(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties><argLine>--enable-preview -XX:+UseZGC</argLine></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().build().testJvm().jvmArgs()).containsExactly("--enable-preview", "-XX:+UseZGC");
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("<argLine>"));
    }

    /**
     * The effective model interpolates {@code ${project.build.outputDirectory}} into this checkout's
     * absolute path; under the module it is written relative, since the test JVM runs in the module
     * directory. A path elsewhere stays as written, with a row.
     */
    @Test
    void a_system_property_path_under_the_module_is_written_relative(@TempDir Path tempDir) throws Exception {
        Path outside = tempDir.resolve("shared/test.conf").toAbsolutePath();
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration>
                      <systemPropertyVariables>
                        <java.util.logging.config.file>${project.build.outputDirectory}/logging.properties</java.util.logging.config.file>
                        <data.dir>${project.basedir}</data.dir>
                        <shared.conf>%s</shared.conf>
                        <mode>test</mode>
                      </systemPropertyVariables>
                    </configuration>
                  </plugin></plugins></build>
                </project>
                """.formatted(outside));

        assertThat(result.jkBuild().build().testJvm().systemProperties())
                .containsExactly(
                        Map.entry("java.util.logging.config.file", "target/classes/logging.properties"),
                        Map.entry("data.dir", "."),
                        Map.entry("shared.conf", outside.toString()),
                        Map.entry("mode", "test"));
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` system property shared.conf=" + outside
                        + " is an absolute path outside the module"))
                .noneMatch(m -> m.contains("java.util.logging.config.file"))
                .noneMatch(m -> m.contains("data.dir"));
    }

    @Test
    void surefire_environment_variables_become_test_env(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration>
                      <environmentVariables>
                        <JAVA_HOME>${java.home}</JAVA_HOME>
                        <FOO_BAR>mapped-env-value</FOO_BAR>
                        <SPACED>value with a newline
                        </SPACED>
                        <DATA_DIR>${project.basedir}/data</DATA_DIR>
                      </environmentVariables>
                    </configuration>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().build().testEnv())
                .containsExactly(
                        new EnvDecl.Set("FOO_BAR", "mapped-env-value"),
                        new EnvDecl.Set("SPACED", "value with a newline"),
                        new EnvDecl.Set("DATA_DIR", "data"));
        assertThat(TestImporters.messages(result))
                .anyMatch(
                        m -> m.contains("environment variable JAVA_HOME") && m.contains("the JDK running the import"));
        assertThat(JkBuildRenderer.render(result.jkBuild()))
                .contains(
                        "env = [{ FOO_BAR = \"mapped-env-value\" }, { SPACED = \"value with a newline\" }, { DATA_DIR = \"data\" }]");
    }

    @Test
    void a_surefire_execution_running_its_own_includes_keeps_its_settings_out_of_test(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration>
                      <systemPropertyVariables><mode>test</mode></systemPropertyVariables>
                    </configuration>
                    <executions>
                      <execution>
                        <id>default-test</id>
                        <configuration><excludes><exclude>**/TestGlobalTags.java</exclude></excludes></configuration>
                      </execution>
                      <execution>
                        <id>check-top-level-tags-only</id>
                        <goals><goal>test</goal></goals>
                        <configuration>
                          <systemPropertyVariables><metrics.tags>myTag=myValue</metrics.tags></systemPropertyVariables>
                          <environmentVariables><tags>topLevel=topValue</tags></environmentVariables>
                          <includes><include>**/TestGlobalTags.java</include></includes>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().build().testJvm().systemProperties()).containsExactly(Map.entry("mode", "test"));
        assertThat(result.jkBuild().build().testEnv()).isEmpty();
        assertThat(result.jkBuild().build().testExcludeClasses()).containsExactly("TestGlobalTags");
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.contains("execution check-top-level-tags-only runs **/TestGlobalTags.java"));
    }

    @Test
    void compiler_proc_none_becomes_a_javac_arg(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration><proc>none</proc></configuration>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().javac().args()).contains("-proc:none");
    }

    @Test
    void failsafe_includes_and_excludes_are_the_integration_suites_classes(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-failsafe-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration>
                      <includes>
                        <include>**/*IntegrationTest.java</include>
                        <include>com/ex/smoke/**/*.java, %regex[.*Flow.*]</include>
                      </includes>
                      <excludes><exclude>**/*SlowIntegrationTest.java</exclude></excludes>
                    </configuration>
                  </plugin></plugins></build>
                </project>
                """);
        List<String> messages = TestImporters.messages(result);

        assertThat(result.jkBuild().build().testSuites())
                .containsExactly(Map.entry(
                        "integration",
                        DeclaredSuite.ofClasses(
                                List.of("*IntegrationTest", "com.ex.smoke.*"), List.of("*SlowIntegrationTest"))));
        assertThat(messages)
                .anyMatch(m -> m.startsWith("maven-failsafe-plugin <includes> %regex[.*Flow.*]: not imported"));
        assertThat(messages).noneMatch(m -> m.startsWith("maven-failsafe-plugin <excludes>"));
        assertThat(result.jkBuild().build().testExcludeClasses())
                .as("Failsafe's excludes narrow its own pass; they do not leave a class out of every suite")
                .isEmpty();
        assertThat(JkBuildRenderer.render(result.jkBuild()))
                .contains("\n[test.suites.integration]\nclasses = [\"*IntegrationTest\", \"com.ex.smoke.*\"]\n"
                        + "exclude-classes = [\"*SlowIntegrationTest\"]\n")
                .doesNotContain("\n[test]\n");
    }

    @Test
    void surefire_file_patterns_map_to_class_patterns() {
        assertThat(TestPlugins.classPattern("**/*PerformanceTest.java")).isEqualTo("*PerformanceTest");
        assertThat(TestPlugins.classPattern("org/acme/**/*IT.class")).isEqualTo("org.acme.*IT");
        assertThat(TestPlugins.classPattern("%ant[**/Legacy*.java]")).isEqualTo("Legacy*");
        assertThat(TestPlugins.classPattern("%regex[.*Slow.*]")).isNull();
        assertThat(TestPlugins.classPattern("**/Test?.java")).isNull();
    }

    @Test
    void surefire_excludes_and_compiler_test_excludes_leave_classes_and_sources_out(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>codec</artifactId>
                  <version>1.0.0</version>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.14.0</version>
                      <configuration>
                        <testExcludes><testExclude>**/*Benchmark*</testExclude></testExcludes>
                        <excludes><exclude>**/package-info.java</exclude></excludes>
                      </configuration>
                    </plugin>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <version>3.5.2</version>
                      <configuration>
                        <includes><include>**/*Test.java</include></includes>
                        <excludes>
                          <exclude>**/*PerformanceTest.java</exclude>
                          <exclude>%regex[.*Flaky.*]</exclude>
                        </excludes>
                      </configuration>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);
        assertThat(build.build().testExcludeClasses()).containsExactly("*PerformanceTest");
        assertThat(build.build().testExcludeSrc()).containsExactly("**/*Benchmark*");
        assertThat(messages)
                .anyMatch(m -> m.startsWith("maven-surefire-plugin <excludes> %regex[.*Flaky.*]: not imported"))
                .anyMatch(m -> m.startsWith("maven-surefire-plugin <includes> **/*Test.java: not imported"))
                .anyMatch(m -> m.startsWith("maven-compiler-plugin <excludes> **/package-info.java: skipped"))
                .noneMatch(m -> m.startsWith("maven-compiler-plugin <testExcludes>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[test]\nexclude-src = [\"**/*Benchmark*\"]\nexclude-classes = [\"*PerformanceTest\"]\n");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.build().testExcludeClasses()).containsExactly("*PerformanceTest");
        assertThat(reparsed.build().testExcludeSrc()).containsExactly("**/*Benchmark*");
    }

    @Test
    void surefire_classpath_dependency_excludes_become_test_exclude_dependencies(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>collector</artifactId>
                  <version>1.0.0</version>
                  <build><plugins>
                    <plugin>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <configuration>
                        <classpathDependencyExcludes>
                          <classpathDependencyExcludes>org.slf4j:slf4j-simple</classpathDependencyExcludes>
                          <classpathDependencyExclude>ch.qos.logback:logback-classic, log4j</classpathDependencyExclude>
                        </classpathDependencyExcludes>
                      </configuration>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        JkBuild build = result.jkBuild();
        assertThat(build.build().testExcludeDependencies())
                .containsExactly("org.slf4j:slf4j-simple", "ch.qos.logback:logback-classic");
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("maven-surefire-plugin <classpathDependencyExcludes> log4j: not imported"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains(
                        "[test]\nexclude-dependencies = [\"org.slf4j:slf4j-simple\", \"ch.qos.logback:logback-classic\"]\n");
        assertThat(JkBuildParser.parse(rendered).build().testExcludeDependencies())
                .containsExactly("org.slf4j:slf4j-simple", "ch.qos.logback:logback-classic");
    }

    @Test
    void classpath_dependency_excludes_managed_in_a_parent_reach_the_module(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <build><pluginManagement><plugins>
                    <plugin>
                      <artifactId>maven-surefire-plugin</artifactId>
                      <configuration>
                        <classpathDependencyExcludes>org.slf4j:slf4j-simple</classpathDependencyExcludes>
                      </configuration>
                    </plugin>
                  </plugins></pluginManagement></build>
                </project>
                """);
        Path pom = Files.createDirectories(tempDir.resolve("core")).resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.ex</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>core</artifactId>
                  <build><plugins>
                    <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
                  </plugins></build>
                </project>
                """);
        PomImporter.Result result = TestImporters.offline(tempDir).importFrom(pom);
        assertThat(result.jkBuild().build().testExcludeDependencies()).containsExactly("org.slf4j:slf4j-simple");
    }

    /**
     * The Quarkus quickstart shape: its {@code *IT} class extends the unit test and runs only under
     * a profile's Failsafe, so {@code mvn test} never runs it — and neither does {@code jk test}.
     */
    @Test
    void an_it_class_no_default_failsafe_runs_is_the_integration_suite(@TempDir Path tempDir) throws Exception {
        Path tests = Files.createDirectories(tempDir.resolve("project/src/test/java/org/acme"));
        Files.writeString(
                tests.resolve("GreetingResourceTest.java"), "package org.acme; class GreetingResourceTest {}\n");
        Files.writeString(tests.resolve("GreetingResourceIT.java"), "package org.acme; class GreetingResourceIT {}\n");
        PomImporter.Result result = TestImporters.importXml(tempDir, PROFILE_ONLY_FAILSAFE);
        assertThat(result.jkBuild().build().testSuites())
                .containsExactly(Map.entry("integration", DeclaredSuite.ofClasses(List.of("IT*", "*IT", "*ITCase"))));
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("IT*, *IT and *ITCase classes:") && m.contains("jk test -s integration"));
    }

    @Test
    void test_sources_without_an_it_class_get_no_integration_suite(@TempDir Path tempDir) throws Exception {
        Path tests = Files.createDirectories(tempDir.resolve("project/src/test/java/org/acme"));
        Files.writeString(
                tests.resolve("GreetingResourceTest.java"), "package org.acme; class GreetingResourceTest {}\n");
        PomImporter.Result result = TestImporters.importXml(tempDir, PROFILE_ONLY_FAILSAFE);
        assertThat(result.jkBuild().build().testSuites()).isEmpty();
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.startsWith("IT*, *IT and *ITCase classes:"));
    }

    @Test
    void failsafes_default_includes_name_the_integration_classes() {
        assertThat(TestPlugins.integrationName("GreetingResourceIT.java")).isTrue();
        assertThat(TestPlugins.integrationName("ITGreeting.java")).isTrue();
        assertThat(TestPlugins.integrationName("GreetingITCase.kt")).isTrue();
        assertThat(TestPlugins.integrationName("GreetingResourceTest.java")).isFalse();
        assertThat(TestPlugins.integrationName("GreetingIT.properties")).isFalse();
    }

    private static final String PROFILE_ONLY_FAILSAFE = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.acme</groupId>
              <artifactId>getting-started</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <profiles>
                <profile>
                  <id>native</id>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <version>3.5.4</version>
                      </plugin>
                    </plugins>
                  </build>
                </profile>
              </profiles>
            </project>
            """;

    private static Path writeManifest(Path tempDir, String rendered) throws Exception {
        Path manifest = tempDir.resolve("rendered").resolve("jk.toml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, rendered);
        return manifest;
    }
}
