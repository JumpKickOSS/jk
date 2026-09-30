// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ReleaseSources;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compiler execution with {@code <multiReleaseOutput>} is a {@code [multi-release]} entry: its
 * {@code <compileSourceRoots>} at its {@code <release>}, from a profile the host JDK activates as
 * much as from the build itself. The jar's own {@code Multi-Release} attribute is then implied.
 */
class PomMultiReleaseImportTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.ex</groupId>
              <artifactId>lib</artifactId>
              <version>1.0.0</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            """;

    @Test
    void an_overlay_execution_in_a_jdk_activated_profile_is_a_multi_release_entry(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-jar-plugin</artifactId>
                      <configuration><archive><manifestEntries>
                        <Multi-Release>true</Multi-Release>
                        <Automatic-Module-Name>com.ex.lib</Automatic-Module-Name>
                      </manifestEntries></archive></configuration>
                    </plugin>
                  </plugins></build>
                  <profiles>
                    <profile>
                      <id>multi-release</id>
                      <activation><jdk>[11,2000)</jdk></activation>
                      <build><plugins>
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <executions>
                            <execution>
                              <id>compile-java11-overlay</id>
                              <phase>compile</phase>
                              <goals><goal>compile</goal></goals>
                              <configuration>
                                <release>11</release>
                                <compileSourceRoots>
                                  <compileSourceRoot>${project.basedir}/src/main/java11</compileSourceRoot>
                                </compileSourceRoots>
                                <multiReleaseOutput>true</multiReleaseOutput>
                              </configuration>
                            </execution>
                          </executions>
                        </plugin>
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-surefire-plugin</artifactId>
                          <configuration>
                            <additionalClasspathElements>
                              <additionalClasspathElement>${project.build.outputDirectory}/META-INF/versions/11</additionalClasspathElement>
                            </additionalClasspathElements>
                          </configuration>
                        </plugin>
                      </plugins></build>
                    </profile>
                  </profiles>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.build().multiRelease()).containsExactly(new ReleaseSources(11, List.of("src/main/java11")));
        assertThat(build.manifest())
                .as("[multi-release] sets the attribute itself")
                .doesNotContainKey("Multi-Release")
                .containsEntry("Automatic-Module-Name", "com.ex.lib");
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("multi-release output"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("\n[multi-release]\n11 = \"src/main/java11\"\n");
        assertThat(JkBuildParser.parse(rendered).build().multiRelease())
                .isEqualTo(build.build().multiRelease());
    }

    @Test
    void an_overlay_without_its_own_root_or_release_is_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <executions>
                        <execution>
                          <id>same-root</id>
                          <goals><goal>compile</goal></goals>
                          <configuration><release>21</release><multiReleaseOutput>true</multiReleaseOutput></configuration>
                        </execution>
                        <execution>
                          <id>no-release</id>
                          <goals><goal>compile</goal></goals>
                          <configuration>
                            <compileSourceRoots><compileSourceRoot>src/main/java21</compileSourceRoot></compileSourceRoots>
                            <multiReleaseOutput>true</multiReleaseOutput>
                          </configuration>
                        </execution>
                      </executions>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().build().multiRelease()).isEmpty();
        List<String> messages = TestImporters.messages(result);
        assertThat(messages)
                .anyMatch(m -> m.startsWith("`maven-compiler-plugin` execution `same-root` writes multi-release output"
                        + " for Java 21 from the main source root"));
        assertThat(messages)
                .anyMatch(m -> m.startsWith(
                        "`maven-compiler-plugin` execution `no-release` writes multi-release output but names no"));
    }
}
