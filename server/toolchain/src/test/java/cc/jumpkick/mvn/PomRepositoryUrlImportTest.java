// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.RepositorySpec;
import java.nio.file.Path;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code <repository><url>} is written in its one normalized spelling — lower-case scheme and
 * host, no default port, one trailing slash — so two POMs naming one origin two ways land as the
 * same {@code [repositories]} entry and the workspace join sees one URL.
 */
class PomRepositoryUrlImportTest {

    @Test
    void a_repository_url_is_written_normalized(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>api</artifactId>
                  <version>1.0.0</version>
                  <repositories>
                    <repository>
                      <id>confluent</id>
                      <url>HTTPS://Packages.Confluent.io:443/maven</url>
                    </repository>
                    <repository>
                      <id>nexus</id>
                      <url>https://nexus.example:8081/repository/releases/</url>
                    </repository>
                  </repositories>
                </project>
                """);

        assertThat(result.jkBuild().repositories())
                .extracting(RepositorySpec::name, r -> r.url().toString())
                .containsExactly(
                        Tuple.tuple("confluent", "https://packages.confluent.io/maven/"),
                        Tuple.tuple("nexus", "https://nexus.example:8081/repository/releases/"));
        assertThat(JkBuildRenderer.render(result.jkBuild()))
                .contains("confluent = \"https://packages.confluent.io/maven/\"");
    }

    /**
     * Maven 3.9 blocks a plaintext {@code http://} repository and builds on while nothing needs it;
     * the import writes it blocked and says so, and the manifest it writes parses.
     */
    @Test
    void a_plaintext_http_repository_is_written_blocked_and_is_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>api</artifactId>
                  <version>1.0.0</version>
                  <repositories>
                    <repository>
                      <id>nm-repo</id>
                      <url>http://repo.numericalmethod.com/maven/</url>
                    </repository>
                    <repository>
                      <id>local</id>
                      <url>http://localhost:8081/maven/</url>
                    </repository>
                  </repositories>
                </project>
                """);

        assertThat(result.jkBuild().repositories())
                .extracting(RepositorySpec::name, RepositorySpec::blocked)
                .containsExactly(Tuple.tuple("nm-repo", true), Tuple.tuple("local", false));
        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.WARNING)
                .extracting(ImportReport.Issue::message)
                .anySatisfy(m -> assertThat(m)
                        .contains("`nm-repo` at http://repo.numericalmethod.com/maven/ is plaintext http")
                        .contains("`blocked = true`")
                        .contains("`allow-insecure = true`"));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered)
                .contains("nm-repo = { url = \"http://repo.numericalmethod.com/maven/\", blocked = true }")
                .contains("local = \"http://localhost:8081/maven/\"");
        assertThat(JkBuildParser.parse(rendered).repositories())
                .extracting(RepositorySpec::name, RepositorySpec::blocked)
                .containsExactly(Tuple.tuple("nm-repo", true), Tuple.tuple("local", false));
    }
}
