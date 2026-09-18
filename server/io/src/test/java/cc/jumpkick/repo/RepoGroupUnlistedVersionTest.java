// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository's catalog can stop short of a release its directory serves (Central lists
 * jfree:jfreechart up to 1.0.1 and serves 1.0.13). A version asked for by name that no catalog
 * lists is probed by its POM, as Maven reads an exact version, and joins the list when found.
 */
class RepoGroupUnlistedVersionTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_wanted_version_the_catalog_omits_is_found_by_its_pom(@TempDir Path tmp) throws Exception {
        RemoteStub remote = new RemoteStub("stale-catalog.example.test");
        new MavenStub(remote.served)
                .metadata("jfree", "jfreechart", "1.0.0", "1.0.1")
                .pomOnly("jfree", "jfreechart", "1.0.13", MavenStub.emptyPom("jfree", "jfreechart", "1.0.13"));
        RepoGroup group = new RepoGroup(List.of(remote.repo(tmp, "remote")));
        Coordinate coord = Coordinate.of("jfree", "jfreechart", "any");

        List<String> versions = group.availableVersions(coord, Set.of("1.0.13"), false);

        assertThat(versions).containsExactlyInAnyOrder("1.0.0", "1.0.1", "1.0.13");
    }

    @Test
    void a_wanted_version_nothing_serves_stays_out(@TempDir Path tmp) throws Exception {
        RemoteStub remote = new RemoteStub("stale-catalog.example.test");
        new MavenStub(remote.served).metadata("jfree", "jfreechart", "1.0.0", "1.0.1");
        RepoGroup group = new RepoGroup(List.of(remote.repo(tmp, "remote")));
        Coordinate coord = Coordinate.of("jfree", "jfreechart", "any");

        List<String> versions = group.availableVersions(coord, Set.of("9.9"), false);

        assertThat(versions).containsExactlyInAnyOrder("1.0.0", "1.0.1");
        assertThat(remote.requestsFor(MavenStub.path("jfree", "jfreechart", "9.9", ".pom")))
                .as("the probe is one POM request")
                .isEqualTo(1);
    }
}
