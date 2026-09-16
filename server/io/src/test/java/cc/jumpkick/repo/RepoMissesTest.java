// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository that answered "not found" for a path is not asked for it again within the session:
 * the second lock on a multi-repository project pays none of the 404s the first one did.
 */
class RepoMissesTest {

    private static final Coordinate LIB = Coordinate.of("com.example", "lib", "1.0");
    private static final String META = MavenStub.metadataPath("com.example", "lib");
    private static final String POM = MavenStub.path("com.example", "lib", "1.0", ".pom");
    private static final String JAR = MavenStub.path("com.example", "lib", "1.0", ".jar");

    /** Holds nothing: every request 404s. */
    @RegisterExtension
    final LoopbackHttp empty = new LoopbackHttp();

    /** Holds the library. */
    @RegisterExtension
    final LoopbackHttp full = new LoopbackHttp();

    @BeforeEach
    void seed() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        SessionContext.reset();
        new MavenStub(full).leaf("com.example", "lib", "1.0");
    }

    @AfterEach
    void reset() {
        SessionContext.reset();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_catalog_a_repository_lacks_is_asked_of_it_once_across_locks(@TempDir Path tmp) throws Exception {
        RepoGroup group = group(tmp);

        assertThat(group.availableVersions(LIB, Set.of("1.0"), false)).containsExactly("1.0");
        // The next lock's version walk: the process memo of the list has expired, the on-disk
        // catalog copy of the repository that has it is within its TTL.
        RepoGroup.clearProcessVersionsCache();
        assertThat(group.availableVersions(LIB, Set.of("1.0"), false)).containsExactly("1.0");

        assertThat(empty.requestsFor(META)).as("the miss was paid once").isEqualTo(1);
        assertThat(full.requestsFor(META))
                .as("the hit came off disk the second time")
                .isEqualTo(1);
    }

    @Test
    void a_pom_and_an_artifact_a_repository_lacks_are_asked_of_it_once(@TempDir Path tmp) {
        MavenRepo repo = repo(tmp, "empty", empty);

        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThatThrownBy(() -> repo.fetchArtifact(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThatThrownBy(() -> repo.fetchArtifact(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);

        assertThat(empty.requestsFor(POM)).isEqualTo(1);
        assertThat(empty.requestsFor(JAR)).isEqualTo(1);
        assertThat(RepoMisses.size()).isEqualTo(2);
    }

    @Test
    void a_miss_speaks_only_for_the_repository_that_answered_it(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(() -> repo(tmp, "empty", empty).fetchPom(LIB))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThat(repo(tmp, "full", full).fetchPom(LIB).url().getPort())
                .isEqualTo(full.base().getPort());
        assertThat(full.requestsFor(POM)).isEqualTo(1);
    }

    @Test
    void a_forced_session_asks_past_the_memo_and_a_hit_clears_it(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp, "late", empty);
        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        // Published since.
        new MavenStub(empty).leaf("com.example", "lib", "1.0");
        assertThatThrownBy(() -> repo.fetchPom(LIB))
                .as("within the TTL the memo answers")
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);

        SessionContext.installConfig(JkConfig.empty().withForce(true));
        assertThat(repo.fetchPom(LIB).url().getPath()).isEqualTo(POM);
        SessionContext.reset();

        assertThat(RepoMisses.size()).as("a hit forgets the miss").isZero();
        assertThat(empty.requestsFor(POM)).isEqualTo(2);
    }

    private RepoGroup group(Path tmp) {
        return new RepoGroup(List.of(repo(tmp, "empty", empty), repo(tmp, "full", full)));
    }

    private static MavenRepo repo(Path tmp, String name, LoopbackHttp server) {
        return new MavenRepo(
                name, server.base(), new Http(), new Cas(tmp.resolve("cas")), RepoCredential.ANONYMOUS, false);
    }
}
