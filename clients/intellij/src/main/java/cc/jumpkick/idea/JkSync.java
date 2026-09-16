// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManager;
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import java.io.File;
import org.jetbrains.annotations.NotNull;

/**
 * Linking and re-resolving a JumpKick project through the external system. Modules are stored
 * externally (the IDE cache), so a linked project never gains {@code .iml} files or
 * {@code .idea/modules.xml} of its own.
 */
public final class JkSync {

    private JkSync() {}

    /** The canonical external-project path for a project base. */
    static String projectPath(@NotNull File base) {
        return ExternalSystemApiUtil.toCanonicalPath(base.getAbsolutePath());
    }

    static boolean isLinked(@NotNull Project project, @NotNull File base) {
        return JkSettings.getInstance(project).getLinkedProjectSettings(projectPath(base)) != null;
    }

    /** Link {@code base} when it is not linked yet; idempotent. */
    public static void link(@NotNull Project project, @NotNull File base) {
        if (isLinked(project, base)) return;
        ExternalProjectsManagerImpl.getInstance(project).setStoreExternally(true);
        JkSettings.getInstance(project).linkProject(new JkProjectSettings(projectPath(base)));
    }

    /**
     * Resolve {@code base} (linking it first when needed). The platform runs
     * {@link JkProjectResolver}, applies the result and reports progress and errors in the Build
     * tool window's Sync tab.
     */
    public static void refresh(@NotNull Project project, @NotNull File base, @NotNull ProgressExecutionMode mode) {
        link(project, base);
        ImportSpecBuilder spec = new ImportSpecBuilder(project, JkSystem.ID).use(mode);
        if (mode == ProgressExecutionMode.IN_BACKGROUND_ASYNC) spec.activateBuildToolWindowOnStart();
        ExternalProjectsManager.getInstance(project)
                .runWhenInitialized(() -> ExternalSystemUtil.refreshProject(projectPath(base), spec));
    }
}
