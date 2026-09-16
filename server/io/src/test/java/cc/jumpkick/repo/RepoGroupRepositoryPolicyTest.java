// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository's release/snapshot policy decides what it is asked for, and a version something
 * asked for by name is looked for past the first catalog that answers, the way Maven asks every
 * repository for an exact version.
 */
class RepoGroupRepositoryPolicyTest {

    @RegisterExtension
    final LoopbackHttp first = new LoopbackHttp().concurrent();

    @RegisterExtension
    final LoopbackHttp second = new LoopbackHttp().concurrent();

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_wanted_version_the_first_catalog_lacks_is_found_in_the_next(@TempDir Path dir) throws Exception {
        new MavenStub(first).metadata("io.dataease", "dataease-license-sdk", "2.10.11", "2.10.10");
        new MavenStub(second).metadata("io.dataease", "dataease-license-sdk", "2.10.26", "2.10.11");
        RepoGroup group = new RepoGroup(List.of(releasesOnly("central", first, dir), repo("fit2cloud", second, dir)));
        Coordinate sdk = Coordinate.of("io.dataease", "dataease-license-sdk", "any");

        assertThat(group.availableVersions(sdk))
                .as("nothing asked for by name: the first catalog that answers is the answer")
                .containsExactly("2.10.11", "2.10.10");
        assertThat(second.requestsFor(MavenStub.metadataPath("io.dataease", "dataease-license-sdk")))
                .isZero();

        assertThat(group.availableVersions(sdk, Set.of("2.10.26"), false))
                .as("the pinned version is missing from the first catalog, so the next one is asked and unioned")
                .containsExactly("2.10.11", "2.10.10", "2.10.26");
    }

    @Test
    void a_snapshot_only_repository_is_not_asked_for_releases_and_never_offers_one(@TempDir Path dir) throws Exception {
        new MavenStub(first).metadata("org.junit.platform", "junit-platform-launcher", "6.2.0-SNAPSHOT");
        new MavenStub(second).metadata("org.junit.platform", "junit-platform-launcher", "6.1.0", "6.0.2");
        RepoGroup group = new RepoGroup(
                List.of(repo("snapshots", first, dir).withPolicy(false, true), releasesOnly("central", second, dir)));
        Coordinate launcher = Coordinate.of("org.junit.platform", "junit-platform-launcher", "any");

        assertThat(group.availableVersions(launcher)).containsExactly("6.1.0", "6.0.2");
        assertThat(first.requestsFor(MavenStub.metadataPath("org.junit.platform", "junit-platform-launcher")))
                .as("a repository whose policy leaves out releases is not asked for them")
                .isZero();
    }

    @Test
    void a_snapshot_is_a_candidate_only_when_wanted_and_only_from_a_snapshot_serving_repository(@TempDir Path dir)
            throws Exception {
        new MavenStub(first).metadata("org.questdb", "questdb-client", "1.3.9", "1.3.10-SNAPSHOT");
        new MavenStub(second).metadata("org.questdb", "questdb-client", "1.3.10-SNAPSHOT");
        RepoGroup group = new RepoGroup(List.of(releasesOnly("central", first, dir), repo("snapshots", second, dir)));
        Coordinate client = Coordinate.of("org.questdb", "questdb-client", "any");

        assertThat(group.availableVersions(client, Set.of("1.3.10-SNAPSHOT"), true))
                .as("central's snapshot entry is dropped by its policy; the snapshot repository's is taken")
                .containsExactly("1.3.9", "1.3.10-SNAPSHOT");
        RepoGroup.clearProcessVersionsCache();
        second.clearRequests();
        assertThat(group.availableVersions(client))
                .as("without a snapshot asked for, no snapshot is a candidate")
                .containsExactly("1.3.9");
        assertThat(second.requestsFor(MavenStub.metadataPath("org.questdb", "questdb-client")))
                .isZero();
    }

    @Test
    void a_snapshot_pom_is_asked_only_of_a_snapshot_serving_repository(@TempDir Path dir) throws Exception {
        new MavenStub(first).leaf("org.questdb", "questdb-client", "1.3.9");
        new MavenStub(second).leaf("org.questdb", "questdb-client", "1.3.10-SNAPSHOT");
        RepoGroup group = new RepoGroup(List.of(releasesOnly("central", first, dir), repo("snapshots", second, dir)));
        Coordinate snapshot = Coordinate.of("org.questdb", "questdb-client", "1.3.10-SNAPSHOT");

        assertThat(group.tryFetchPom(snapshot))
                .hasValueSatisfying(hit -> assertThat(hit.repo().name()).isEqualTo("snapshots"));
        assertThat(first.requestsFor(MavenStub.path("org.questdb", "questdb-client", "1.3.10-SNAPSHOT", ".pom")))
                .as("a releases-only repository is never asked for a snapshot")
                .isZero();
    }

    private static MavenRepo repo(String name, LoopbackHttp http, Path dir) {
        return new MavenRepo(name, http.base(), new Http(), new Cas(dir.resolve("cache")));
    }

    private static MavenRepo releasesOnly(String name, LoopbackHttp http, Path dir) {
        return repo(name, http, dir).withPolicy(true, false);
    }
}
