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
 * The process memo outlives the group that filled it: a later job's group naming the same URL its
 * own way gets the hit answered by its own repository, under its own name.
 */
class RepoGroupMemoRebindTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_memo_hit_names_the_asking_group_s_repository(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path dir = repo.resolve("org/demo/lib/1.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("lib-1.0.pom"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>org.demo</groupId><artifactId>lib</artifactId><version>1.0</version></project>
                """);
        Cas cas = new Cas(tmp.resolve("cas"));
        Coordinate lib = Coordinate.of("org.demo", "lib", "1.0");

        MavenRepo first = new MavenRepo("corp-a", repo.toUri(), new Http(), cas);
        assertThat(new RepoGroup(List.of(first)).tryFetchPom(lib).orElseThrow().repo())
                .isSameAs(first);

        MavenRepo second = new MavenRepo("corp-b", repo.toUri(), new Http(), cas);
        RepoGroup.RepoFetched hit =
                new RepoGroup(List.of(second)).tryFetchPom(lib).orElseThrow();
        assertThat(hit.repo()).isSameAs(second);
        assertThat(hit.repo().name()).isEqualTo("corp-b");
    }
}
