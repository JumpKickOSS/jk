// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Google Android Maven routes the Android groups; only what a user binds on it is exclusive. */
class RepoGroupBuilderGoogleExclusiveTest {

    @Test
    void built_in_google_maven_routes_androidx_and_claims_nothing_exclusively() {
        assertThat(RepositorySpec.GOOGLE_MAVEN.hasExclusiveGroups()).isFalse();
        assertThat(RepoGroupBuilder.routedGroupsFor(RepositorySpec.GOOGLE_MAVEN))
                .contains("androidx.*", "com.google.android.*", "com.google.firebase");
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(RepositorySpec.GOOGLE_MAVEN))
                .isEmpty();
    }

    @Test
    void user_declared_google_without_groups_gets_the_routed_defaults() {
        RepositorySpec bare = new RepositorySpec("google", URI.create("https://dl.google.com/dl/android/maven2/"));
        assertThat(bare.hasExclusiveGroups()).isFalse();
        assertThat(RepoGroupBuilder.routedGroupsFor(bare)).isEqualTo(RepositorySpec.GOOGLE_ANDROID_GROUPS);
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(bare)).isEmpty();
    }

    @Test
    void user_explicit_groups_on_google_are_exclusive_on_top_of_the_routed_defaults() {
        RepositorySpec custom = new RepositorySpec(
                "google",
                URI.create("https://dl.google.com/dl/android/maven2/"),
                null,
                null,
                List.of("com.google.gms", "androidx.*"));
        assertThat(RepoGroupBuilder.routedGroupsFor(custom)).isEqualTo(RepositorySpec.GOOGLE_ANDROID_GROUPS);
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(custom)).containsExactly("com.google.gms", "androidx.*");
    }

    @Test
    void central_has_no_default_binding() {
        assertThat(RepoGroupBuilder.exclusiveGroupsFor(RepositorySpec.MAVEN_CENTRAL))
                .isEmpty();
        assertThat(RepoGroupBuilder.routedGroupsFor(RepositorySpec.MAVEN_CENTRAL))
                .isEmpty();
    }

    @Test
    void detects_google_by_host() {
        RepositorySpec mavenGoogle = new RepositorySpec("g", URI.create("https://maven.google.com/"));
        assertThat(RepoGroupBuilder.isGoogleAndroidMaven(mavenGoogle)).isTrue();
    }
}
