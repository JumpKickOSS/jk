// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
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
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wire-only JumpKick actions: spawn {@code jk} on PATH (ticket-1054). Never loads engine jars into
 * the IDE process.
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
        if (base == null || !new File(base, "jk.toml").isFile()) {
            balloon(project, "No jk.toml in project base — open a JumpKick project root", NotificationType.ERROR);
            return;
        }
        ProgressManager.getInstance()
                .run(new Task.Backgroundable(project, "JumpKick: " + title, true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        indicator.setText("jk " + String.join(" ", args));
                        int code = runJk(base, args, indicator);
                        ApplicationManager.getApplication()
                                .invokeLater(() -> {
                                    if (code == 0) {
                                        balloon(project, title + " succeeded", NotificationType.INFORMATION);
                                    } else {
                                        balloon(
                                                project,
                                                title + " failed (exit " + code + ") — see Run tool window / logs",
                                                NotificationType.ERROR);
                                    }
                                });
                    }
                });
    }

    private static int runJk(File cwd, List<String> args, ProgressIndicator indicator) {
        String bin = System.getenv().getOrDefault("JK_BIN", "jk");
        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(bin);
        cmd.addParameters(args);
        cmd.setWorkDirectory(cwd);
        cmd.setCharset(StandardCharsets.UTF_8);
        try {
            OSProcessHandler handler = new OSProcessHandler(cmd);
            StringBuilder out = new StringBuilder();
            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    out.append(event.getText());
                    indicator.setText2(trimLine(event.getText()));
                }
            });
            handler.startNotify();
            // Wait with cancel support.
            while (!handler.isProcessTerminated()) {
                if (indicator.isCanceled()) {
                    handler.destroyProcess();
                    return 130;
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handler.destroyProcess();
                    return 130;
                }
            }
            return handler.getExitCode() == null ? 1 : handler.getExitCode();
        } catch (Exception ex) {
            balloon(
                    null,
                    "Failed to start '"
                            + bin
                            + "': "
                            + ex.getMessage()
                            + " — install jk and ensure PATH / JK_BIN",
                    NotificationType.ERROR);
            return 1;
        }
    }

    private static String trimLine(String text) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() > 80 ? t.substring(0, 77) + "…" : t;
    }

    @Nullable
    private static File projectBase(Project project) {
        String path = project.getBasePath();
        if (path == null) return null;
        return new File(path);
    }

    private static void balloon(@Nullable Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("JumpKick")
                .createNotification(message, type)
                .notify(project);
    }

    public static final class BspInstall extends JkCliAction {
        public BspInstall() {
            super("Install BSP connection", "bsp", "install");
        }
    }

    public static final class Sync extends JkCliAction {
        public Sync() {
            super("Sync", "sync");
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
}
