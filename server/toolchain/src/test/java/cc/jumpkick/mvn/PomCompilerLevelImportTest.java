// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java level Maven compiles at is the level {@code java =} takes: the {@code maven.compiler.*}
 * properties, else the compiler plugin's {@code <release>} / {@code <target>} / {@code <source>} —
 * read from {@code <pluginManagement>} too, since the jar lifecycle binds the compiler plugin
 * whether or not {@code <plugins>} lists it, and Maven configures the bound run from there.
 */
class PomCompilerLevelImportTest {

    @Test
    void a_compiler_plugin_configured_under_plugin_management_alone_sets_the_level(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.iluwatar</groupId>
                  <artifactId>prototype</artifactId>
                  <version>1.26.0-SNAPSHOT</version>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <version>3.14.0</version>
                          <configuration>
                            <source>21</source>
                            <target>21</target>
                          </configuration>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().project().java())
                .as("the managed configuration is the bound compile's configuration")
                .isEqualTo(21);
    }

    @Test
    void a_declared_compiler_plugin_takes_the_managed_configuration_it_leaves_unsaid(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <version>3.14.0</version>
                          <configuration>
                            <release>17</release>
                          </configuration>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.jkBuild().project().java()).isEqualTo(17);
    }
}
