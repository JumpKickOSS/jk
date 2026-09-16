// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static java.util.Objects.requireNonNull;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The trigger wiring: VFS events for a lockfile and a manifest under the project base reach the
 * watcher, a burst costs one debounced sync, and an unrelated file schedules nothing.
 */
public class JkManifestWatcherVfsTest extends HeavyPlatformTestCase {

    public void test_a_lockfile_change_schedules_one_debounced_sync() throws Exception {
        Path base = Files.createDirectories(Path.of(requireNonNull(getProject().getBasePath())));
        Files.writeString(base.resolve("jk.toml"), "name = \"app\"\n");
        VirtualFile baseVf = requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base));

        JkSyncDebouncerTest.FakeScheduler scheduler = new JkSyncDebouncerTest.FakeScheduler();
        int[] syncs = {0};
        JkManifestWatcher watcher = new JkManifestWatcher(
                getProject(), new JkSyncDebouncer(JkSyncDebouncer.QUIET_MS, scheduler, () -> syncs[0]++));
        disposeOnTearDown(watcher);

        WriteAction.run(() -> {
            VfsUtil.saveText(baseVf.createChildData(this, "jk-lock.toml"), "# lock\n");
            VfsUtil.saveText(requireNonNull(baseVf.findChild("jk.toml")), "name = \"app\"\n[dependencies]\n");
        });
        assertTrue("the manifest change reached the watcher", watcher.isPending());
        assertEquals("nothing runs before the quiet window closes", 0, syncs[0]);
        assertTrue("one scheduling per event: " + scheduler.tasks.size(), scheduler.tasks.size() >= 2);
        for (int i = 0; i < scheduler.tasks.size(); i++) scheduler.fire(i);
        assertEquals("one sync for the burst", 1, syncs[0]);
        assertFalse(watcher.isPending());

        WriteAction.run(() -> VfsUtil.saveText(baseVf.createChildData(this, "notes.txt"), "unrelated\n"));
        assertFalse("an unrelated file does not schedule a sync", watcher.isPending());
    }
}
