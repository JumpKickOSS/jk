// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import java.io.File;
import org.jetbrains.annotations.NotNull;

/**
 * On project open with a {@code jk.toml} at the base: link the JumpKick project (first open) and
 * resolve it once, silently, with progress in the Build tool window; then keep the manifests
 * watched. No prompt and no generated project files.
 */
public final class JkProjectOpenActivity implements StartupActivity.DumbAware, DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> open(project));
    }

    private static void open(Project project) {
        if (project.isDisposed()) return;
        File base = JkCliAction.projectBase(project);
        if (base == null || !JkBin.isJumpKickRoot(base)) return;
        JkManifestWatcher.getInstance(project);
        JkSync.refresh(project, base, ProgressExecutionMode.IN_BACKGROUND_ASYNC);
    }
}
