// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file this repository downloaded and verified in this job is not confirmed again when a second
 * caller finds it in the store: its checksum sidecars are asked for once. The second caller is the
 * one that reaches the group after the first caller's download placed the file and before the first
 * caller wrote the group's memo, the state this test sets up by fetching through the repository
 * alone; under load a descheduled thread leaves that state by itself, which is how a lock came to
 * ask for a jar's {@code .sha256} and {@code .sha1} twice.
 */
class StoreCopyConfirmedOnceTest {

    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        new MavenStub(http).leaf("com.example", "widget", "1.0").jar("com.example", "widget", "1.0");
    }

    @AfterEach
    void clear() {
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_jar_this_repository_verified_is_not_confirmed_again_from_the_store(@TempDir Path dir) throws Exception {
        MavenRepo repo = new MavenRepo("central", http.base(), new Http(), new Cas(dir.resolve("cas")));

        MavenRepo.Fetched first = repo.fetchArtifact(WIDGET);
        var second = RepoGroup.of(repo).tryFetchArtifact(WIDGET);

        assertThat(second)
                .hasValueSatisfying(hit -> assertThat(hit.fetched().sha256()).isEqualTo(first.sha256()));
        assertThat(http.requested()).as("each file is asked for once").doesNotHaveDuplicates();
    }
}
