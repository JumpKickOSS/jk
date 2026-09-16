// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

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
import org.jetbrains.annotations.Nullable;

/**
 * Runs {@code jk <task…>} for the platform's external-system task machinery, streaming both
 * streams into the run console. Task names are jk verbs ({@code build}, {@code test}).
 */
public final class JkTaskManager implements ExternalSystemTaskManager<JkExecutionSettings> {

    private final Map<ExternalSystemTaskId, EmptyProgressIndicator> running = new ConcurrentHashMap<>();

    @Override
    public void executeTasks(
            @NotNull ExternalSystemTaskId id,
            @NotNull List<String> taskNames,
            @NotNull String projectPath,
            @Nullable JkExecutionSettings settings,
            @Nullable String jvmParametersSetup,
            @NotNull ExternalSystemTaskNotificationListener listener)
            throws ExternalSystemException {
        List<String> args = new ArrayList<>(taskNames);
        if (settings != null) args.addAll(settings.getArguments());
        listener.onTaskOutput(id, JkBin.path() + " " + String.join(" ", args) + "\n", true);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        running.put(id, indicator);
        JkCliRunner.Result run;
        try {
            run = JkCliRunner.run(
                    new File(projectPath), args, indicator, line -> listener.onTaskOutput(id, line + "\n", true));
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
