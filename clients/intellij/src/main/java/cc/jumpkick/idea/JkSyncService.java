// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JumpKick Sync: lock/materialize via engine-backed CLI, apply project model by generating
 * IntelliJ files from the same engine {@code ide-model} as BSP, refresh BSP connection, reload VFS.
 *
 * <p>The plugin never loads engine jars. Structured model
 * comes from {@code jk ide --print-model}; on-disk module mapping reuses {@code jk ide --idea}
 * (shared generator). BSP stays dual-path via {@code .bsp/jk.json}.
 */
public final class JkSyncService {

    public record SyncResult(
            boolean success,
            @Nullable String message,
            @Nullable JkWireModel model,
            int exitCode) {}

    private JkSyncService() {}

    public static @NotNull SyncResult sync(
            @NotNull Project project, @NotNull File projectDir, @NotNull ProgressIndicator indicator) throws Exception {
        indicator.setIndeterminate(true);
        indicator.setText("JumpKick: resolving model (lock + sync)");

        // 1) Machine model — proves engine handshake + gives module counts for UI.
        JkCliRunner.Result modelRun = JkCliRunner.run(projectDir, List.of("ide", "--print-model"), indicator);
        if (indicator.isCanceled()) {
            return new SyncResult(false, "cancelled", null, 130);
        }
        JkWireModel model = null;
        if (modelRun.ok() && modelRun.stdout() != null && !modelRun.stdout().isBlank()) {
            model = JkWireModel.parse(modelRun.stdout());
            if (model.error != null && !model.error.isBlank()) {
                return new SyncResult(false, model.error, model, 2);
            }
        } else if (!modelRun.ok()) {
            String err = firstNonBlank(modelRun.stderr(), modelRun.stdout(), "jk ide --print-model failed");
            return new SyncResult(false, err + " (exit " + modelRun.exitCode() + ")", null, modelRun.exitCode());
        }

        if (indicator.isCanceled()) {
            return new SyncResult(false, "cancelled", model, 130);
        }

        // 2) Apply IntelliJ project structure from the same engine model (generator path).
        indicator.setText("JumpKick: applying IntelliJ project structure");
        JkCliRunner.Result ideaRun = JkCliRunner.run(projectDir, List.of("ide", "--idea"), indicator);
        if (!ideaRun.ok()) {
            String err = firstNonBlank(ideaRun.stderr(), ideaRun.stdout(), "jk ide --idea failed");
            return new SyncResult(false, err + " (exit " + ideaRun.exitCode() + ")", model, ideaRun.exitCode());
        }

        if (indicator.isCanceled()) {
            return new SyncResult(false, "cancelled", model, 130);
        }

        // 3) Ensure BSP connection (ide --idea also writes it; refresh is cheap/idempotent).
        indicator.setText("JumpKick: refreshing BSP connection");
        JkCliRunner.Result bspRun = JkCliRunner.run(projectDir, List.of("bsp", "install"), indicator);
        if (!bspRun.ok()) {
            String err = firstNonBlank(bspRun.stderr(), bspRun.stdout(), "jk bsp install failed");
            return new SyncResult(false, err + " (exit " + bspRun.exitCode() + ")", model, bspRun.exitCode());
        }

        // 4) Refresh VFS so IDEA picks up .idea / *.iml / .bsp
        indicator.setText("JumpKick: refreshing project files");
        refreshVfs(projectDir);

        int modules = model != null ? model.moduleCount() : 0;
        String msg = modules > 0
                ? "Synced " + modules + " module" + (modules == 1 ? "" : "s")
                        + (model != null && !model.rootName.isBlank() ? " (" + model.rootName + ")" : "")
                : "JumpKick project synced";
        return new SyncResult(true, msg, model, 0);
    }

    private static void refreshVfs(@NotNull File projectDir) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectDir);
            if (vf != null) {
                vf.refresh(false, true);
            }
            // Also poke .idea and module roots
            File idea = new File(projectDir, ".idea");
            VirtualFile ideaVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(idea);
            if (ideaVf != null) ideaVf.refresh(false, true);
        });
    }

    private static String firstNonBlank(String... parts) {
        if (parts == null) return "";
        for (String p : parts) {
            if (p != null) {
                String t = p.strip();
                if (!t.isEmpty()) {
                    // Prefer last non-empty line for wedge output.
                    String[] lines = t.split("\n");
                    for (int i = lines.length - 1; i >= 0; i--) {
                        if (!lines[i].isBlank()) return lines[i].strip();
                    }
                    return t;
                }
            }
        }
        return "";
    }
}
