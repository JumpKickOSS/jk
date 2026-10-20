// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Runs {@code jk <task…>} for the platform's external-system task machinery, streaming both
 * streams into the run console. Task names are jk verbs ({@code build}, {@code test}).
 */
public final class JkTaskManager implements ExternalSystemTaskManager<JkExecutionSettings> {

    private final Map<ExternalSystemTaskId, EmptyProgressIndicator> running = new ConcurrentHashMap<>();

    /** The task names and the arguments both ride on {@code settings}; the platform sets them. */
    @Override
    public void executeTasks(
            @NotNull String projectPath,
            @NotNull ExternalSystemTaskId id,
            @NotNull JkExecutionSettings settings,
            @NotNull ExternalSystemTaskNotificationListener listener)
            throws ExternalSystemException {
        List<String> taskNames = settings.getTasks();
        List<String> args = new ArrayList<>(taskNames);
        args.addAll(settings.getArguments());
        listener.onTaskOutput(id, JkBin.path() + " " + String.join(" ", args) + "\n", ProcessOutputType.STDOUT);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        running.put(id, indicator);
        JkCliRunner.Result run;
        try {
            run = JkCliRunner.run(
                    new File(projectPath),
                    args,
                    indicator,
                    line -> listener.onTaskOutput(id, line + "\n", ProcessOutputType.STDOUT));
        } catch (Exception e) {
            throw new ExternalSystemException("Failed to start '" + JkBin.path() + "': " + e.getMessage(), e);
        } finally {
            running.remove(id);
        }
        if (indicator.isCanceled()) return;
        if (!run.ok()) {
            throw new ExternalSystemException(
                    JkCliLines.firstErrorLine(run.stderr(), null, "jk " + String.join(" ", taskNames) + " failed")
                            + " (exit " + run.exitCode() + ")");
        }
    }

    @Override
    public boolean cancelTask(
            @NotNull ExternalSystemTaskId id, @NotNull ExternalSystemTaskNotificationListener listener) {
        EmptyProgressIndicator indicator = running.get(id);
        if (indicator == null) return false;
        indicator.cancel();
        return true;
    }
}
