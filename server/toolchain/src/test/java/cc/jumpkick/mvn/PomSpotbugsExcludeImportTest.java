// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where {@code spotbugs-maven-plugin}'s filter files land: one {@code [lint] spotbugs-exclude} list. */
class PomSpotbugsExcludeImportTest {

    /**
     * hadoop-benchmark's shape: {@code <excludeFilterFiles>} names the module's filter and the
     * reactor root's through the launcher property, and the parent's {@code <excludeFilterFile>}
     * still applies — one {@code spotbugs-exclude} list of all three, by their paths from the module.
     */
    @Test
    void every_spotbugs_filter_file_of_both_parameters_is_excluded(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.apache.hadoop</groupId>
                  <artifactId>hadoop-main</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>hadoop-benchmark</module></modules>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <configuration><excludeFilterFile>dev-support/parent.xml</excludeFilterFile></configuration>
                        <executions><execution><goals><goal>check</goal></goals></execution></executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        Files.createDirectories(root.resolve("dev-support"));
        Files.writeString(root.resolve("dev-support/findbugs-exclude-global.xml"), "<FindBugsFilter/>");
        Files.writeString(root.resolve("dev-support/parent.xml"), "<FindBugsFilter/>");
        Path benchmark = Files.createDirectories(root.resolve("hadoop-benchmark"));
        Files.createDirectories(benchmark.resolve("src/main/findbugs"));
        Files.writeString(benchmark.resolve("src/main/findbugs/exclude.xml"), "<FindBugsFilter/>");
        Files.writeString(benchmark.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.apache.hadoop</groupId>
                    <artifactId>hadoop-main</artifactId>
                    <version>1.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>hadoop-benchmark</artifactId>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>com.github.spotbugs</groupId>
                        <artifactId>spotbugs-maven-plugin</artifactId>
                        <configuration>
                          <excludeFilterFiles>
                            <excludeFilterFile>${basedir}/src/main/findbugs/exclude.xml</excludeFilterFile>
                            <excludeFilterFile>${maven.multiModuleProjectDirectory}/dev-support/findbugs-exclude-global.xml
                            </excludeFilterFile>
                          </excludeFilterFiles>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        PomImporter.WorkspaceImportResult workspace =
                TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        PluginConfig lint = Objects.requireNonNull(workspace.modules().get("hadoop-benchmark"))
                .pluginConfig("lint")
                .orElseThrow();
        assertThat(lint.values())
                .containsEntry(
                        "spotbugs-exclude",
                        List.of(
                                "../dev-support/parent.xml",
                                "src/main/findbugs/exclude.xml",
                                "../dev-support/findbugs-exclude-global.xml"));
    }
}
