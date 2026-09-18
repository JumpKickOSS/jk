// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
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

    @Test
    void the_override_stands_in_for_the_derived_id_until_cleared() {
        try {
            BuildIdentity.overrideBuildIdForTests("feedfacecafe");
            assertThat(BuildIdentity.buildId()).isEqualTo("feedfacecafe");
        } finally {
            BuildIdentity.overrideBuildIdForTests(null);
        }
        assertThat(BuildIdentity.buildId()).isEmpty();
    }

    /**
     * Two builds of one version are ordered by the build time the packaging wrote into the
     * archive's manifest, which a reinstall cannot move the way it moves a file's mtime; an archive
     * without the attribute, or with one that is not an instant, has no time.
     */
    @Test
    void the_build_time_is_the_manifests_attribute_or_nothing() {
        Manifest stamped = new Manifest();
        stamped.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        stamped.getMainAttributes().putValue(BuildIdentity.BUILD_TIME_ATTRIBUTE, "2026-09-17T15:57:16Z");
        assertThat(BuildIdentity.builtAt(stamped)).isEqualTo(Instant.parse("2026-09-17T15:57:16Z"));

        Manifest bare = new Manifest();
        bare.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        assertThat(BuildIdentity.builtAt(bare)).isNull();

        Manifest garbled = new Manifest();
        garbled.getMainAttributes().putValue(BuildIdentity.BUILD_TIME_ATTRIBUTE, "yesterday");
        assertThat(BuildIdentity.builtAt(garbled)).isNull();
    }

    @Test
    void unit_tests_run_from_a_classes_dir_and_have_no_build_time() {
        assertThat(BuildIdentity.builtAt()).isNull();
    }
}
