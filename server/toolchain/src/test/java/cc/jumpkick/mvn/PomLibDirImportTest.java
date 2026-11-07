// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.LibDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code maven-dependency-plugin:copy-dependencies} with {@code maven-jar-plugin}'s {@code
 * addClasspath} / {@code classpathPrefix} is {@code [application] lib-dir}: Helidon's application
 * parent ships every quickstart that way, as {@code target/<artifactId>.jar} plus {@code target/libs/}.
 */
class PomLibDirImportTest {

    /** Helidon's shape: the execution is configured in pluginManagement and bound by id in the module. */
    private static final String HELIDON = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.helidon.examples.quickstarts</groupId>
              <artifactId>helidon-quickstart-se</artifactId>
              <version>1.0.0-SNAPSHOT</version>
              <properties><mainClass>io.helidon.examples.quickstart.se.Main</mainClass></properties>
              <build>
                <finalName>${project.artifactId}</finalName>
                <pluginManagement><plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-dependency-plugin</artifactId>
                    <version>3.8.1</version>
                    <executions><execution>
                      <id>copy-libs</id>
                      <phase>prepare-package</phase>
                      <goals><goal>copy-dependencies</goal></goals>
                      <configuration>
                        <outputDirectory>${project.build.directory}/libs</outputDirectory>
                        <overWriteReleases>false</overWriteReleases>
                        <overWriteSnapshots>false</overWriteSnapshots>
                        <overWriteIfNewer>true</overWriteIfNewer>
                        <includeScope>runtime</includeScope>
                        <excludeArtifactIds>okio</excludeArtifactIds>
                      </configuration>
                    </execution></executions>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.4.2</version>
                    <configuration><archive><manifest>
                      <addClasspath>true</addClasspath>
                      <classpathPrefix>libs</classpathPrefix>
                      <mainClass>${mainClass}</mainClass>
                      <useUniqueVersions>false</useUniqueVersions>
                    </manifest></archive></configuration>
                  </plugin>
                </plugins></pluginManagement>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-dependency-plugin</artifactId>
                    <executions><execution><id>copy-libs</id></execution></executions>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @Test
    void helidons_copy_libs_is_the_lib_dir_layout_with_no_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HELIDON);
        JkBuild build = result.jkBuild();

        LibDir libDir = Objects.requireNonNull(build.libDir());
        assertThat(libDir.dir()).isEqualTo("libs");
        assertThat(libDir.versions()).isTrue();
        assertThat(libDir.exclude()).containsExactly("okio");
        assertThat(build.mainClass()).isEqualTo("io.helidon.examples.quickstart.se.Main");
        assertThat(TestImporters.messages(result))
                .noneMatch(m -> m.contains("maven-dependency-plugin"))
                .noneMatch(m -> m.contains("classpathPrefix"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("lib-dir  = true\n").contains("lib-exclude = [\"okio\"]\n");
        LibDir reparsed = Objects.requireNonNull(JkBuildParser.parse(rendered).libDir());
        assertThat(reparsed).isEqualTo(libDir);
    }

    @Test
    void strip_version_group_exclusions_and_a_named_directory_carry_over(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build><plugins>
                    <plugin>
                      <artifactId>maven-dependency-plugin</artifactId>
                      <executions><execution>
                        <goals><goal>copy-dependencies</goal></goals>
                        <configuration>
                          <outputDirectory>${project.build.directory}/lib/</outputDirectory>
                          <stripVersion>true</stripVersion>
                          <excludeGroupIds>org.unwanted</excludeGroupIds>
                          <excludeTransitive>true</excludeTransitive>
                        </configuration>
                      </execution></executions>
                    </plugin>
                    <plugin>
                      <artifactId>maven-jar-plugin</artifactId>
                      <configuration><archive><manifest>
                        <addClasspath>true</addClasspath>
                        <classpathPrefix>deps/</classpathPrefix>
                        <mainClass>com.ex.Main</mainClass>
                      </manifest></archive></configuration>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        LibDir libDir = Objects.requireNonNull(result.jkBuild().libDir());
        List<String> messages = TestImporters.messages(result);

        assertThat(libDir.dir()).isEqualTo("lib");
        assertThat(libDir.versions()).isFalse();
        assertThat(libDir.exclude()).containsExactly("org.unwanted:*");
        assertThat(messages).anyMatch(m -> m.startsWith("`maven-dependency-plugin` `<excludeTransitive>`"));
        assertThat(messages).anyMatch(m -> m.startsWith("`maven-jar-plugin` `<classpathPrefix>` is `deps/`"));
        assertThat(messages).noneMatch(m -> m.startsWith("maven-dependency-plugin not imported"));
    }

    @Test
    void a_library_copying_its_dependencies_writes_no_layout_and_says_why(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                  <build><plugins><plugin>
                    <artifactId>maven-dependency-plugin</artifactId>
                    <executions><execution>
                      <goals><goal>copy-dependencies</goal></goals>
                    </execution></executions>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().libDir()).isNull();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.contains("target/dependency") && m.contains("names no main class"));
    }

    @Test
    void another_goal_keeps_the_generic_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <properties><exec.mainClass>com.ex.Main</exec.mainClass></properties>
                  <build><plugins><plugin>
                    <artifactId>maven-dependency-plugin</artifactId>
                    <executions>
                      <execution><id>copy</id><goals><goal>copy-dependencies</goal></goals></execution>
                      <execution><id>tree</id><goals><goal>tree</goal></goals></execution>
                    </executions>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(Objects.requireNonNull(result.jkBuild().libDir()).dir()).isEqualTo("dependency");
        assertThat(TestImporters.messages(result)).anyMatch(m -> m.startsWith("maven-dependency-plugin not imported"));
    }
}
