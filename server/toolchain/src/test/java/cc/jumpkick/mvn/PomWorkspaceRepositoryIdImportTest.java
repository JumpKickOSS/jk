// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.model.RepositorySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven keys a repository id per module; a workspace resolves against one set keyed by id. Two
 * members giving one id two URLs would refuse the lock, so the later member's repository is
 * renamed with a suffix and a row says so, while one URL spelled two ways stays one repository.
 */
class PomWorkspaceRepositoryIdImportTest {

    @Test
    void a_member_reusing_an_id_at_another_url_gets_its_repository_renamed(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>a</module><module>b</module><module>c</module></modules>
                </project>
                """);
        member(root, "a", "http://localhost:8081/repository/maven-releases/");
        member(root, "b", "http://localhost:8081/repository/maven-snapshots/");
        member(root, "c", "HTTP://LOCALHOST:8081/repository/maven-releases");

        PomImporter.WorkspaceImportResult ws = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(ws.modules().get("a")).repositories())
                .extracting(RepositorySpec::name)
                .containsExactly("nexus");
        assertThat(requireNonNull(ws.modules().get("b")).repositories())
                .extracting(RepositorySpec::name, r -> r.url().toString())
                .containsExactly(tuple("nexus-2", "http://localhost:8081/repository/maven-snapshots/"));
        assertThat(requireNonNull(ws.modules().get("c")).repositories())
                .as("one URL spelled two ways is one repository")
                .extracting(RepositorySpec::name)
                .containsExactly("nexus");
        assertThat(ws.root().repositories()).extracting(RepositorySpec::name).containsExactly("nexus", "nexus-2");
        assertThat(ws.report().issues()).extracting(i -> i.message()).anySatisfy(m -> assertThat(m)
                .contains("`nexus` is http://localhost:8081/repository/maven-snapshots/ in `b`")
                .contains("written `nexus-2`"));
    }

    private static void member(Path root, String name, String url) throws Exception {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name).resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.ex</groupId><artifactId>parent</artifactId><version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  <repositories>
                    <repository>
                      <id>nexus</id>
                      <url>%s</url>
                    </repository>
                  </repositories>
                </project>
                """.formatted(name, url));
    }
}
