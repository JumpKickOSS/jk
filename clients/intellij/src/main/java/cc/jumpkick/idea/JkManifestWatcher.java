// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.File;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Re-resolves the project when a {@code jk.toml} or {@code jk-lock.toml} under it changes, from
 * the editor or from a terminal ({@code jk add}), after a {@value JkSyncDebouncer#QUIET_MS} ms
 * quiet window. Starts watching when first requested for a project and stops with it.
 */
@Service(Service.Level.PROJECT)
public final class JkManifestWatcher implements Disposable {

    private static final List<String> MANIFESTS = List.of("jk.toml", "jk-lock.toml");

    private final JkSyncDebouncer debouncer;

    public JkManifestWatcher(@NotNull Project project) {
        this(project, new JkSyncDebouncer(JkSyncDebouncer.QUIET_MS, JkManifestWatcher::later, () -> refresh(project)));
    }

    JkManifestWatcher(@NotNull Project project, @NotNull JkSyncDebouncer debouncer) {
        this.debouncer = debouncer;
        String base = project.getBasePath();
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    if (isManifest(event.getPath(), base)) {
                        debouncer.touch();
                        return;
                    }
                }
            }
        });
    }

    public static JkManifestWatcher getInstance(@NotNull Project project) {
        return project.getService(JkManifestWatcher.class);
    }

    /** A manifest or lockfile under {@code base}, outside any {@code target} tree. */
    static boolean isManifest(String path, @Nullable String base) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        if (!MANIFESTS.contains(p.substring(slash + 1))) return false;
        if (base == null) return false;
        String b = base.replace('\\', '/');
        if (!p.startsWith(b.endsWith("/") ? b : b + "/")) return false;
        return !p.contains("/target/");
    }

    boolean isPending() {
        return debouncer.isPending();
    }

    private static Future<?> later(Runnable task, long delayMs) {
        return AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void refresh(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            String basePath = project.getBasePath();
            if (basePath == null) return;
            File base = new File(basePath);
            if (!JkBin.isJumpKickRoot(base)) return;
            JkSync.refresh(project, base, ProgressExecutionMode.IN_BACKGROUND_ASYNC);
        });
    }

    @Override
    public void dispose() {}
}
