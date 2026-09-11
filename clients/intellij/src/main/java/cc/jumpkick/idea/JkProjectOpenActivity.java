// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import java.io.File;
import org.jetbrains.annotations.NotNull;

/**
 * On project open: if {@code jk.toml} is present, offer (or auto-run when no modules yet) JumpKick
 * Sync so users never need to manually run {@code jk ide}.
 */
public final class JkProjectOpenActivity implements StartupActivity.DumbAware, DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> maybePrompt(project));
    }

    private static void maybePrompt(Project project) {
        if (project.isDisposed()) return;
        File base = JkCliAction.projectBase(project);
        if (base == null || !JkBin.isJumpKickRoot(base)) return;

        boolean hasIdeaModules = new File(base, ".idea/modules.xml").isFile() || hasIml(base);
        Notification n = NotificationGroupManager.getInstance()
                .getNotificationGroup("JumpKick")
                .createNotification(
                        hasIdeaModules
                                ? "JumpKick project detected — Sync to refresh modules and classpath"
                                : "JumpKick project detected — Sync to import modules (no manual jk ide needed)",
                        NotificationType.INFORMATION);
        n.addAction(new NotificationAction("Sync JumpKick project") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                notification.expire();
                runSync(project, base);
            }
        });
        if (!hasIdeaModules) {
            n.addAction(new NotificationAction("Dismiss") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                    notification.expire();
                }
            });
            n.notify(project);
            // Auto-import when no IDEA modules exist yet.
            runSync(project, base);
        } else {
            n.notify(project);
        }
    }

    private static boolean hasIml(File base) {
        File[] files = base.listFiles((dir, name) -> name.endsWith(".iml"));
        return files != null && files.length > 0;
    }

    private static void runSync(Project project, File base) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "JumpKick: Sync", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    JkSyncService.SyncResult result = JkSyncService.sync(project, base, indicator);
                    ApplicationManager.getApplication()
                            .invokeLater(() -> JkCliAction.balloon(
                                    project,
                                    result.message() != null
                                            ? result.message()
                                            : (result.success() ? "Sync succeeded" : "Sync failed"),
                                    result.success() ? NotificationType.INFORMATION : NotificationType.ERROR));
                } catch (Exception ex) {
                    ApplicationManager.getApplication()
                            .invokeLater(() -> JkCliAction.balloon(
                                    project, "Sync failed: " + ex.getMessage(), NotificationType.ERROR));
                }
            }
        });
    }
}
