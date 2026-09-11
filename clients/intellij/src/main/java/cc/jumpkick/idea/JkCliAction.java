// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wire-only JumpKick actions: spawn {@code jk} on PATH. Never loads engine jars into the IDE
 * process.
 */
public abstract class JkCliAction extends AnAction implements DumbAware {

    private final List<String> args;
    private final String title;

    protected JkCliAction(String title, String... args) {
        super(title);
        this.title = title;
        this.args = List.copyOf(Arrays.asList(args));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            balloon(null, "No open project", NotificationType.ERROR);
            return;
        }
        File base = projectBase(project);
        if (base == null || !JkBin.isJumpKickRoot(base)) {
            balloon(project, "No jk.toml in project base — open a JumpKick project root", NotificationType.ERROR);
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "JumpKick: " + title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("jk " + String.join(" ", args));
                try {
                    JkCliRunner.Result result = JkCliRunner.run(base, args, indicator);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (result.ok()) {
                            balloon(project, title + " succeeded", NotificationType.INFORMATION);
                        } else {
                            balloon(
                                    project,
                                    title + " failed (exit " + result.exitCode() + ") — see Run tool window / logs",
                                    NotificationType.ERROR);
                        }
                    });
                } catch (Exception ex) {
                    ApplicationManager.getApplication()
                            .invokeLater(() -> balloon(
                                    project,
                                    "Failed to start '" + JkBin.path() + "': " + ex.getMessage()
                                            + " — install jk and ensure PATH / JK_BIN",
                                    NotificationType.ERROR));
                }
            }
        });
    }

    static @Nullable File projectBase(Project project) {
        if (project == null) return null;
        String path = project.getBasePath();
        return path == null ? null : new File(path);
    }

    static void balloon(@Nullable Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("JumpKick")
                .createNotification(message, type)
                .notify(project);
    }

    /** Full Sync: print-model + ide --idea + bsp install + VFS refresh. */
    public static final class Sync extends AnAction implements DumbAware {
        public Sync() {
            super("Sync project");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project == null) {
                balloon(null, "No open project", NotificationType.ERROR);
                return;
            }
            File base = projectBase(project);
            if (base == null || !JkBin.isJumpKickRoot(base)) {
                balloon(project, "No jk.toml in project base — open a JumpKick project root", NotificationType.ERROR);
                return;
            }
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "JumpKick: Sync", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        JkSyncService.SyncResult result = JkSyncService.sync(project, base, indicator);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (result.success()) {
                                balloon(
                                        project,
                                        result.message() != null ? result.message() : "Sync succeeded",
                                        NotificationType.INFORMATION);
                            } else {
                                balloon(
                                        project,
                                        result.message() != null ? result.message() : "Sync failed",
                                        NotificationType.ERROR);
                            }
                        });
                    } catch (Exception ex) {
                        ApplicationManager.getApplication()
                                .invokeLater(() -> balloon(
                                        project,
                                        "Sync failed: " + ex.getMessage() + " — install jk and ensure PATH / JK_BIN",
                                        NotificationType.ERROR));
                    }
                }
            });
        }
    }

    public static final class BspInstall extends JkCliAction {
        public BspInstall() {
            super("Install BSP connection", "bsp", "install");
        }
    }

    public static final class Build extends JkCliAction {
        public Build() {
            super("Build", "build");
        }
    }

    public static final class Test extends JkCliAction {
        public Test() {
            super("Test", "test");
        }
    }

    public static final class Lock extends JkCliAction {
        public Lock() {
            super("Lock", "lock");
        }
    }

    /** Materialize lock artifacts only (deps). */
    public static final class SyncDeps extends JkCliAction {
        public SyncDeps() {
            super("Sync dependencies only", "sync");
        }
    }
}
