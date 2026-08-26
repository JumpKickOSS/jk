// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildIdentityTest {

    private static final String SALTED = "#" + BuildIdentity.CACHE_KEY_SALT;

    @Test
    void snapshots_fold_the_build_id_in_releases_do_not() {
        assertThat(BuildIdentity.compose("1.0.0", "abc123def456")).isEqualTo("1.0.0" + SALTED);
        assertThat(BuildIdentity.compose("1.0.0-SNAPSHOT", "abc123def456"))
                .isEqualTo("1.0.0-SNAPSHOT" + SALTED + "+abc123def456");
        // No derivable identity -> the version-string rule stands (release behavior).
        assertThat(BuildIdentity.compose("1.0.0-SNAPSHOT", "")).isEqualTo("1.0.0-SNAPSHOT" + SALTED);
    }

    @Test
    void turning_the_salt_turns_every_key_release_branch_included() {
        // Release-shaped versions drop the jar id, so the salt is the only turnable input there.
        assertThat(BuildIdentity.compose("1.0.0", "", BuildIdentity.CACHE_KEY_SALT))
                .isNotEqualTo(BuildIdentity.compose("1.0.0", "", BuildIdentity.CACHE_KEY_SALT + 1));
        assertThat(BuildIdentity.compose("1.0.0-SNAPSHOT", "abc123def456", BuildIdentity.CACHE_KEY_SALT))
                .isNotEqualTo(
                        BuildIdentity.compose("1.0.0-SNAPSHOT", "abc123def456", BuildIdentity.CACHE_KEY_SALT + 1));
        // The salt reaches cacheKeyVersion() through the same composition rule.
        assertThat(BuildIdentity.cacheKeyVersion())
                .isEqualTo(BuildIdentity.compose(JkVersion.VERSION, BuildIdentity.buildId()));
    }

    @Test
    void unit_tests_run_from_a_classes_dir_and_get_no_jar_identity() {
        assertThat(BuildIdentity.buildId()).isEmpty();
        assertThat(BuildIdentity.cacheKeyVersion()).isEqualTo(JkVersion.VERSION + SALTED);
    }
}
