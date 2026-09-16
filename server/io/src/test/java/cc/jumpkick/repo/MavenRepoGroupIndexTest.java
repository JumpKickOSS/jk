// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Nexus group repository answers {@code org/jboss/jandex/maven-metadata.xml} with the plugin
 * index of the {@code org.jboss.jandex} group: no ids, no versioning. That repository contributes
 * no versions of {@code org.jboss:jandex}, and the repository that lists them still answers.
 */
class MavenRepoGroupIndexTest {

    private static final String GROUP_INDEX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata modelVersion="1.1.0">
              <plugins>
                <plugin>
                  <name>Jandex wrapper for Maven</name>
                  <prefix>jandex</prefix>
                  <artifactId>jandex-maven-plugin</artifactId>
                </plugin>
              </plugins>
            </metadata>
            """;

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_group_index_without_versioning_contributes_no_versions(@TempDir Path tmp) throws Exception {
        Path groupRepo = tmp.resolve("group");
        write(groupRepo, GROUP_INDEX);
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo repo = new MavenRepo("jboss-public", groupRepo.toUri(), new Http(), cas);

        assertThat(repo.availableVersions(Coordinate.of("org.jboss", "jandex", "0")))
                .isEmpty();
    }

    @Test
    void the_repository_that_lists_versions_answers_beside_a_group_index(@TempDir Path tmp) throws Exception {
        Path groupRepo = tmp.resolve("group");
        write(groupRepo, GROUP_INDEX);
        Path central = tmp.resolve("central");
        write(central, """
                <metadata>
                  <groupId>org.jboss</groupId>
                  <artifactId>jandex</artifactId>
                  <versioning>
                    <versions>
                      <version>2.4.3.Final</version>
                      <version>3.1.2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(List.of(
                new MavenRepo("jboss-public", groupRepo.toUri(), new Http(), cas),
                new MavenRepo("central", central.toUri(), new Http(), cas)));

        assertThat(group.availableVersions(Coordinate.of("org.jboss", "jandex", "0")))
                .containsExactlyInAnyOrder("2.4.3.Final", "3.1.2");
    }

    private static void write(Path root, String metadata) throws Exception {
        Path dir = root.resolve("org/jboss/jandex");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("maven-metadata.xml"), metadata);
    }
}
