// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepoGroupBuilderGoogleExclusiveTest {

    @Test
    void built_in_google_maven_claims_androidx() {
        assertThat(RepositorySpec.GOOGLE_MAVEN.hasExclusiveGroups()).isTrue();
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(RepositorySpec.GOOGLE_MAVEN))
                .contains("androidx.*", "com.google.android.*");
    }

    @Test
    void user_declared_google_without_groups_gets_defaults() {
        RepositorySpec bare = new RepositorySpec("google", URI.create("https://dl.google.com/dl/android/maven2/"));
        assertThat(bare.hasExclusiveGroups()).isFalse();
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(bare)).isEqualTo(RepositorySpec.GOOGLE_ANDROID_EXCLUSIVE_GROUPS);
    }

    @Test
    void user_explicit_groups_on_google_win() {
        RepositorySpec custom = new RepositorySpec(
                "google",
                URI.create("https://dl.google.com/dl/android/maven2/"),
                Optional.empty(),
                Optional.empty(),
                List.of("androidx.compose.*"));
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(custom)).containsExactly("androidx.compose.*");
    }

    @Test
    void central_has_no_default_exclusive() {
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(RepositorySpec.MAVEN_CENTRAL)).isEmpty();
    }

    @Test
    void detects_google_by_host() {
        RepositorySpec mavenGoogle =
                new RepositorySpec("g", URI.create("https://maven.google.com/"));
        assertThat(RepoGroupBuilder.isGoogleAndroidMaven(mavenGoogle)).isTrue();
    }
}
