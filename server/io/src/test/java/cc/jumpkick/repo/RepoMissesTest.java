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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A remote repository that answered "not found" for a path is not asked for it again within the
 * session: the second lock on a multi-repository project pays none of the 404s the first one did.
 * The remotes here are in-memory transports under remote-looking hosts, because a loopback stub is
 * asked afresh every time ({@link LoopbackRepoMemoTest}).
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

    /** A remote that holds nothing. */
    private final RemoteStub emptyRemote = new RemoteStub("empty.example.test");

    /** A remote that holds the library. */
    private final RemoteStub fullRemote = new RemoteStub("full.example.test");

    @BeforeEach
    void seed() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        SessionContext.reset();
        new MavenStub(full).leaf("com.example", "lib", "1.0");
        new MavenStub(fullRemote.served).leaf("com.example", "lib", "1.0");
    }

    @AfterEach
    void reset() {
        SessionContext.reset();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_catalog_miss_is_asked_again_because_a_catalog_answers_once_something_is_published(@TempDir Path tmp)
            throws Exception {
        RepoGroup group = group(tmp);

        assertThat(group.availableVersions(LIB, Set.of("1.0"), false)).containsExactly("1.0");
        // Published on the repository that had nothing; the next walk (the list memo expired) sees it.
        new MavenStub(empty).metadata("com.example", "lib", "2.0");
        RepoGroup.clearProcessVersionsCache();
        assertThat(group.availableVersions(LIB, Set.of("1.0"), false)).containsExactlyInAnyOrder("1.0", "2.0");

        assertThat(empty.requestsFor(META)).as("a catalog is asked every time").isEqualTo(2);
        // A loopback repository's list is revalidated on every ask (a conditional GET), never
        // served for the day from the on-disk copy: the port names whatever process holds it now.
        assertThat(full.requestsFor(META))
                .as("a loopback catalog is asked again the second time")
                .isEqualTo(2);
        assertThat(RepoMisses.size()).as("catalogs are not memoized").isZero();
    }

    @Test
    void a_pom_and_an_artifact_a_repository_lacks_are_asked_of_it_once(@TempDir Path tmp) {
        MavenRepo repo = emptyRemote.repo(tmp, "empty");

        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThatThrownBy(() -> repo.fetchArtifact(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThatThrownBy(() -> repo.fetchArtifact(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);

        assertThat(emptyRemote.requestsFor(POM)).isEqualTo(1);
        assertThat(emptyRemote.requestsFor(JAR)).isEqualTo(1);
        assertThat(RepoMisses.size()).isEqualTo(2);
    }

    @Test
    void a_miss_speaks_only_for_the_repository_that_answered_it(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(() -> emptyRemote.repo(tmp, "empty").fetchPom(LIB))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThat(fullRemote.repo(tmp, "full").fetchPom(LIB).url().getHost()).isEqualTo("full.example.test");
        assertThat(fullRemote.requestsFor(POM)).isEqualTo(1);
    }

    @Test
    void a_forced_session_asks_past_the_memo_and_a_hit_clears_it(@TempDir Path tmp) throws Exception {
        MavenRepo repo = emptyRemote.repo(tmp, "late");
        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        // Published since.
        new MavenStub(emptyRemote.served).leaf("com.example", "lib", "1.0");
        assertThatThrownBy(() -> repo.fetchPom(LIB))
                .as("within the TTL the memo answers")
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);

        SessionContext.installConfig(JkConfig.empty().withForce(true));
        assertThat(repo.fetchPom(LIB).url().getPath()).isEqualTo(POM);
        SessionContext.reset();

        assertThat(RepoMisses.size()).as("a hit forgets the miss").isZero();
        assertThat(emptyRemote.requestsFor(POM)).isEqualTo(2);
    }

    /**
     * A miss is keyed by the URL the request opened, so a settings.xml mirror that starts routing the
     * repository is asked afresh for what the repository itself missed: the memo written at the
     * origin never answers for the mirror, and the mirror's for the origin.
     */
    @Test
    void a_settings_mirror_is_asked_afresh_for_what_the_repository_missed_before_it_existed(@TempDir Path tmp)
            throws Exception {
        MavenRepo central = emptyRemote.repo(tmp, "central");
        assertThatThrownBy(() -> central.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThat(RepoMisses.size()).isEqualTo(1);

        // ~/.m2/settings.xml gains a <mirror> for central: the same repository, its requests opened elsewhere.
        MavenRepo mirrored = central.mirroredThrough(
                new MavenRepo.Mirror("nexus", full.base(), RepoCredential.ANONYMOUS, "mirror `nexus`"));

        assertThat(mirrored.name()).isEqualTo("central");
        assertThat(mirrored.fetchPom(LIB).url().getHost()).isEqualTo("127.0.0.1");
        assertThat(emptyRemote.requestsFor(POM))
                .as("the origin was asked once, before the mirror")
                .isEqualTo(1);
        assertThat(full.requestsFor(POM)).isEqualTo(1);
    }

    /**
     * A {@code file://} repository is the disk right now — a workspace path, a git materialization —
     * and what it lacks it can gain without a session boundary, so its not-found answers are never
     * held: the artifact it gains is seen on the next ask, with no {@code --force}.
     */
    @Test
    void a_file_repository_that_gains_an_artifact_is_seen_without_force(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("local"));
        MavenRepo local = new MavenRepo(
                "local", dir.toUri(), new Http(), new Cas(tmp.resolve("cas")), RepoCredential.ANONYMOUS, false);
        assertThat(RepoMisses.memoizes(dir.toUri()))
                .as("a file:// repository is not memoized")
                .isFalse();

        assertThatThrownBy(() -> local.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThat(RepoMisses.size()).as("the miss was not kept").isZero();

        // Published since, by a path or git materialization writing into the directory.
        Path pom = dir.resolve("com/example/lib/1.0/lib-1.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion></project>");

        assertThat(local.fetchPom(LIB).url().getPath()).endsWith("/com/example/lib/1.0/lib-1.0.pom");
    }

    private RepoGroup group(Path tmp) {
        return new RepoGroup(List.of(repo(tmp, "empty", empty), repo(tmp, "full", full)));
    }

    private static MavenRepo repo(Path tmp, String name, LoopbackHttp server) {
        return new MavenRepo(
                name, server.base(), new Http(), new Cas(tmp.resolve("cas")), RepoCredential.ANONYMOUS, false);
    }
}
