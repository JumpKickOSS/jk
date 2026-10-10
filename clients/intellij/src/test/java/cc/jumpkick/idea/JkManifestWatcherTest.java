// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which VFS paths count as a manifest change for a project base. */
class JkManifestWatcherTest {

    @Test
    void manifests_and_lockfiles_under_the_base_count() {
        assertThat(JkManifestWatcher.isManifest("/w/app/jk.toml", "/w/app")).isTrue();
        assertThat(JkManifestWatcher.isManifest("/w/app/core/jk-lock.toml", "/w/app"))
                .isTrue();
        assertThat(JkManifestWatcher.isManifest("C:\\w\\app\\core\\jk.toml", "C:/w/app/"))
                .isTrue();
    }

    @Test
    void other_files_other_projects_and_build_outputs_do_not() {
        assertThat(JkManifestWatcher.isManifest("/w/app/core/src/A.java", "/w/app"))
                .isFalse();
        assertThat(JkManifestWatcher.isManifest("/w/other/jk.toml", "/w/app")).isFalse();
        assertThat(JkManifestWatcher.isManifest("/w/app2/jk.toml", "/w/app")).isFalse();
        assertThat(JkManifestWatcher.isManifest("/w/app/target/sandbox/jk.toml", "/w/app"))
                .isFalse();
        assertThat(JkManifestWatcher.isManifest("/w/app/jk.toml", null)).isFalse();
    }
}
