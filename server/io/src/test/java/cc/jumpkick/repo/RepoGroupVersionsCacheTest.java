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

    @Test
    void availableVersions_memo_is_scoped_to_repository_set(@TempDir Path tmp) throws Exception {
        Path repoA = tmp.resolve("a");
        Path repoB = tmp.resolve("b");
        writeMeta(repoA, "com.example", "lib", "1.0");
        writeMeta(repoB, "com.example", "lib", "2.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup groupA = new RepoGroup(List.of(new MavenRepo("a", repoA.toUri(), new Http(), cas)));
        RepoGroup groupB = new RepoGroup(List.of(new MavenRepo("b", repoB.toUri(), new Http(), cas)));

        assertThat(groupA.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("1.0");
        // Different repo set must not reuse group A's answer.
        assertThat(groupB.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("2.0");
    }

    @Test
    void pom_hit_memo_is_scoped_to_repository_set(@TempDir Path tmp) throws Exception {
        Path repoA = tmp.resolve("a");
        Path repoB = tmp.resolve("b");
        writePom(repoA, "com.example", "lib", "1.0", "from-a");
        writePom(repoB, "com.example", "lib", "1.0", "from-b");
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup groupA = new RepoGroup(List.of(new MavenRepo("a", repoA.toUri(), new Http(), cas)));
        RepoGroup groupB = new RepoGroup(List.of(new MavenRepo("b", repoB.toUri(), new Http(), cas)));
        Coordinate gav = Coordinate.of("com.example", "lib", "1.0");

        String a =
                Files.readString(groupA.tryFetchPom(gav).orElseThrow().fetched().cachePath());
        String b =
                Files.readString(groupB.tryFetchPom(gav).orElseThrow().fetched().cachePath());
        assertThat(a).contains("from-a");
        assertThat(b).contains("from-b");
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

    private static void writePom(Path root, String group, String artifact, String version, String name)
            throws Exception {
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        String body = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                </project>
                """.formatted(group, artifact, version, name);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), body);
    }
}
