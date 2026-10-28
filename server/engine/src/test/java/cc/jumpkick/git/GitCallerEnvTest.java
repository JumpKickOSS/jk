// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitCallerEnvTest {

    @Test
    void the_callers_transport_and_auth_settings_reach_git() {
        assertThat(GitCliExtension.passes("GIT_SSH_COMMAND")).isTrue();
        assertThat(GitCliExtension.passes("GIT_ASKPASS")).isTrue();
        assertThat(GitCliExtension.passes("GIT_SSL_CAINFO")).isTrue();
        assertThat(GitCliExtension.passes("GIT_TRACE")).isTrue();
    }

    @Test
    void a_hooks_repository_location_never_reaches_jks_checkouts() {
        // `jk guard` from a pre-commit hook runs with these pointing at the user's checkout.
        assertThat(GitCliExtension.passes("GIT_DIR")).isFalse();
        assertThat(GitCliExtension.passes("GIT_INDEX_FILE")).isFalse();
        assertThat(GitCliExtension.passes("GIT_WORK_TREE")).isFalse();
        assertThat(GitCliExtension.passes("GIT_OBJECT_DIRECTORY")).isFalse();
    }

    @Test
    void the_config_channel_jk_injects_credentials_through_stays_jks() {
        assertThat(GitCliExtension.passes("GIT_CONFIG_COUNT")).isFalse();
        assertThat(GitCliExtension.passes("GIT_CONFIG_KEY_0")).isFalse();
        assertThat(GitCliExtension.passes("GIT_CONFIG_VALUE_0")).isFalse();
        assertThat(GitCliExtension.passes("GIT_CONFIG_PARAMETERS")).isFalse();
        assertThat(GitCliExtension.passes("GIT_CONFIG_GLOBAL"))
                .as("the caller's own config file")
                .isTrue();
    }

    @Test
    void nothing_outside_git_s_namespace_passes() {
        assertThat(GitCliExtension.passes("JK_REPO_GH_TOKEN")).isFalse();
        assertThat(GitCliExtension.passes("AWS_SECRET_ACCESS_KEY")).isFalse();
    }
}
