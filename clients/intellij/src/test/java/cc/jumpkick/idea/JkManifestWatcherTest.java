// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which VFS paths count as a manifest change for a project base. */
public class JkManifestWatcherTest {

    @Test
    public void manifests_and_lockfiles_under_the_base_count() {
        assertTrue(JkManifestWatcher.isManifest("/w/app/jk.toml", "/w/app"));
        assertTrue(JkManifestWatcher.isManifest("/w/app/core/jk-lock.toml", "/w/app"));
        assertTrue(JkManifestWatcher.isManifest("C:\\w\\app\\core\\jk.toml", "C:/w/app/"));
    }

    @Test
    public void other_files_other_projects_and_build_outputs_do_not() {
        assertFalse(JkManifestWatcher.isManifest("/w/app/core/src/A.java", "/w/app"));
        assertFalse(JkManifestWatcher.isManifest("/w/other/jk.toml", "/w/app"));
        assertFalse(JkManifestWatcher.isManifest("/w/app2/jk.toml", "/w/app"));
        assertFalse(JkManifestWatcher.isManifest("/w/app/target/sandbox/jk.toml", "/w/app"));
        assertFalse(JkManifestWatcher.isManifest("/w/app/jk.toml", null));
    }
}
