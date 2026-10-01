// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.ClassSuite;
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
        assertThat(messages).noneMatch(m -> m.startsWith("`maven-surefire-plugin` `<argLine>`"));
        assertThat(messages).noneMatch(m -> m.startsWith("`maven-surefire-plugin` system properties"));
        assertThat(build.build().testExcludeClasses())
                .as("the file pattern becomes the class pattern Maven's runner skips")
                .containsExactly("*Slow*");
        assertThat(messages).noneMatch(m -> m.startsWith("`maven-surefire-plugin` `<excludes>`"));
        assertThat(build.build().testClassSuites())
                .as("failsafe's default patterns are the integration suite's classes")
                .containsExactly(Map.entry("integration", ClassSuite.of(List.of("IT*", "*IT", "*ITCase"))));
        assertThat(messages).noneMatch(m -> m.startsWith("`maven-failsafe-plugin` runs"));
        assertThat(messages).noneMatch(m -> m.contains("src/integration/java"));
        assertThat(messages)
                .as("failsafe's argLine differs from surefire's, and [test] jvm-args is one list")
                .anyMatch(m -> m.startsWith("`maven-failsafe-plugin` `<argLine>` -Xmx2g —")
                        && m.contains("Surefire's -Xmx1g -Dfile.encoding=UTF-8 is in it"));
        assertThat(messages)
                .anyMatch(m -> m.startsWith("`jacoco-maven-plugin` —") && m.contains("`jk test --coverage`"));
        assertThat(messages).noneMatch(m -> m.contains("skip tests"));
        assertThat(messages)
                .as("surefire, failsafe and jacoco each have their own rows, never the generic one")
                .noneMatch(m -> m.startsWith("`<plugin>"));

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
        assertThat(reparsed.build().testClassSuites()).isEqualTo(build.build().testClassSuites());
        assertThat(JkBuildParser.parseTestTags(writeManifest(tempDir, rendered)).excludeTags())
                .as("the engine's root-scoped reader sees the same filters")
                .containsExactly("slow");
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
                .anyMatch(m ->
                        m.startsWith("`maven-surefire-plugin` `<groups>fast & !flaky</groups>` is a tag expression"));
        assertThat(messages).anyMatch(m -> m.contains("skip tests") && m.contains("`jk build --skip-tests`"));
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
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("`<argLine>`"));
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

        assertThat(result.jkBuild().build().testClassSuites())
                .containsExactly(Map.entry(
                        "integration",
                        new ClassSuite(
                                List.of("*IntegrationTest", "com.ex.smoke.*"), List.of("*SlowIntegrationTest"))));
        assertThat(messages).anyMatch(m -> m.startsWith("`maven-failsafe-plugin` `<includes>` %regex[.*Flow.*] —"));
        assertThat(messages).noneMatch(m -> m.startsWith("`maven-failsafe-plugin` `<excludes>`"));
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
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` `<excludes>` %regex[.*Flaky.*] —"))
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` `<includes>` **/*Test.java —"))
                .anyMatch(m -> m.startsWith("`maven-compiler-plugin` `<excludes>` **/package-info.java —"))
                .noneMatch(m -> m.startsWith("`maven-compiler-plugin` `<testExcludes>`"));

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
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` `<classpathDependencyExcludes>` log4j —"));

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

    private static Path writeManifest(Path tempDir, String rendered) throws Exception {
        Path manifest = tempDir.resolve("rendered").resolve("jk.toml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, rendered);
        return manifest;
    }
}
