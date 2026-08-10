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

class RepoGroupVersionsCacheTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void availableVersions_process_memo_hits_second_call(@TempDir Path tmp) throws Exception {
        Path repoDir = tmp.resolve("repo");
        writeMeta(repoDir, "com.example", "lib", "1.0", "2.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo repo = new MavenRepo("local", repoDir.toUri(), new Http(), cas);
        RepoGroup group = new RepoGroup(List.of(repo));

        List<String> first = group.availableVersions(Coordinate.of("com.example", "lib", "0"));
        assertThat(first).containsExactlyInAnyOrder("1.0", "2.0");

        // Delete on-disk metadata — process memo must still answer.
        Files.walk(repoDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
        List<String> second = group.availableVersions(Coordinate.of("com.example", "lib", "0"));
        assertThat(second).isEqualTo(first);

        RepoGroup.clearProcessVersionsCache();
        assertThat(group.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .isEmpty();
    }

    private static void writeMeta(Path root, String group, String artifact, String... versions) throws Exception {
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        Files.createDirectories(dir);
        StringBuilder vs = new StringBuilder();
        for (String v : versions) {
            vs.append("    <version>").append(v).append("</version>\n");
        }
        String body = """
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, vs);
        Files.writeString(dir.resolve("maven-metadata.xml"), body);
    }
}
