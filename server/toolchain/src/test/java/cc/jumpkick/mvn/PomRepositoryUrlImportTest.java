// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
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
}
