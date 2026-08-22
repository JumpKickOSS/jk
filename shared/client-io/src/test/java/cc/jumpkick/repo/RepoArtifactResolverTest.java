// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepoArtifactResolverTest {

    @Test
    void user_remote_named_local_is_a_named_remote() {
        assertThat(RepoArtifactResolver.isNamedRemote("local")).isTrue();
    }

    @Test
    void jk_local_is_not_a_named_remote() {
        assertThat(RepoArtifactResolver.isNamedRemote(RepoArtifactResolver.JK_LOCAL))
                .isFalse();
        assertThat(RepoArtifactResolver.isNamedRemote("jk-local")).isFalse();
    }

    @Test
    void first_party_source_is_only_jk_local() {
        assertThat(RepoArtifactResolver.isFirstPartySource("jk-local")).isTrue();
        assertThat(RepoArtifactResolver.isFirstPartySource(RepoArtifactResolver.JK_LOCAL))
                .isTrue();
        assertThat(RepoArtifactResolver.isFirstPartySource("local")).isFalse();
        assertThat(RepoArtifactResolver.isFirstPartySource("local+https://example/"))
                .isFalse();
        assertThat(RepoArtifactResolver.isFirstPartySource("central+https://repo.maven.apache.org/maven2/"))
                .isFalse();
        assertThat(RepoArtifactResolver.isFirstPartyStoreName("jk-local")).isTrue();
        assertThat(RepoArtifactResolver.isFirstPartyStoreName("local")).isFalse();
    }

    @Test
    void git_prefix_is_not_a_named_remote() {
        assertThat(RepoArtifactResolver.isNamedRemote("git:gh:foo/bar:1.0")).isFalse();
    }
}
