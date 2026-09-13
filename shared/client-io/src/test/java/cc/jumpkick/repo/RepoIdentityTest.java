// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** A repository's store identity comes from its origin; the name a project gives it plays no part. */
class RepoIdentityTest {

    @Test
    void the_canonical_origin_drops_everything_that_does_not_change_which_bytes_are_served() {
        assertThat(RepoIdentity.canonicalOrigin(URI.create("HTTPS://User:tok@Repo.Acme.com:443/maven/?x=1#f")))
                .isEqualTo("https://repo.acme.com/maven");
        assertThat(RepoIdentity.canonicalOrigin(URI.create("https://repo.acme.com/maven")))
                .isEqualTo("https://repo.acme.com/maven");
        assertThat(RepoIdentity.canonicalOrigin(URI.create("http://repo.acme.com:8081/maven/")))
                .isEqualTo("http://repo.acme.com:8081/maven");
        assertThat(RepoIdentity.canonicalOrigin(URI.create("file:///srv/repo/")))
                .isEqualTo("file:///srv/repo");
    }

    @Test
    void the_three_public_origins_keep_their_reserved_directories() {
        assertThat(RepoIdentity.storeId(RepositorySpec.MAVEN_CENTRAL.url())).isEqualTo(RepositorySpec.CENTRAL);
        assertThat(RepoIdentity.storeId(RepositorySpec.GOOGLE_MAVEN.url())).isEqualTo(RepositorySpec.GOOGLE);
        assertThat(RepoIdentity.storeId(RepositorySpec.JUMPKICK.url())).isEqualTo(RepositorySpec.JUMPKICK_NAME);
        assertThat(RepoIdentity.storeId(URI.create("https://repo.maven.apache.org/maven2")))
                .as("a spelling difference is not a different origin")
                .isEqualTo(RepositorySpec.CENTRAL);
    }

    @Test
    void two_origins_get_two_ids_and_one_origin_gets_one_whatever_it_is_called() {
        String a = RepoIdentity.storeId(URI.create("https://nexus.acme.com/repository/maven-releases/"));
        String b = RepoIdentity.storeId(URI.create("https://artifactory.other.io/libs-release/"));
        String aAgain = RepoIdentity.storeId(URI.create("https://Nexus.Acme.com/repository/maven-releases"));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(aAgain);
        assertThat(a).startsWith("nexus.acme.com-").matches(".+-[0-9a-f]{12}");
        assertThat(b).startsWith("artifactory.other.io-");
    }

    @Test
    void a_mirror_under_a_public_name_is_not_the_public_origin() {
        String mirror = RepoIdentity.storeId(URI.create("https://mirror.corp.example/maven2/"));

        assertThat(mirror).isNotEqualTo(RepositorySpec.CENTRAL).startsWith("mirror.corp.example-");
    }

    @Test
    void an_unparseable_origin_still_yields_a_stable_safe_id() {
        String id = RepoIdentity.storeId("http://bad host/with space");

        assertThat(id)
                .isEqualTo(RepoIdentity.storeId("http://bad host/with space"))
                .matches("[a-z0-9.-]+-[0-9a-f]{12}");
        assertThat(id).doesNotContain("/").doesNotContain(" ");
    }

    @Test
    void a_store_id_is_recognisable_as_one_among_group_segments() {
        assertThat(RepoIdentity.looksLikeStoreId("central")).isTrue();
        assertThat(RepoIdentity.looksLikeStoreId("jk-local")).isTrue();
        assertThat(RepoIdentity.looksLikeStoreId("nexus.acme.com-0123456789ab")).isTrue();
        assertThat(RepoIdentity.looksLikeStoreId("org")).isFalse();
        assertThat(RepoIdentity.looksLikeStoreId("private")).isFalse();
    }
}
