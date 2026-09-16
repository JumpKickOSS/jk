// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sync: {@code jk ide --print-model} in the linked directory, decoded by {@link JkWireModel},
 * SDKs registered, and the {@link JkProjectGraph} handed to the platform, which applies it and
 * shows the run in the Build tool window. A failing CLI surfaces as one line: its first error
 * line. Wire-only: nothing of the engine is loaded into the IDE process.
 */
public final class JkProjectResolver implements ExternalSystemProjectResolver<JkExecutionSettings> {

    private static final List<String> PRINT_MODEL = List.of("ide", "--print-model");

    private final Map<ExternalSystemTaskId, EmptyProgressIndicator> running = new ConcurrentHashMap<>();

    @Override
    public @Nullable DataNode<ProjectData> resolveProjectInfo(
            @NotNull ExternalSystemTaskId id,
            @NotNull String projectPath,
            boolean isPreviewMode,
            @Nullable JkExecutionSettings settings,
            @NotNull ExternalSystemTaskNotificationListener listener)
            throws ExternalSystemException {
        if (isPreviewMode) {
            ProjectData preview =
                    new ProjectData(JkSystem.ID, new File(projectPath).getName(), projectPath, projectPath);
            return new DataNode<>(ProjectKeys.PROJECT, preview, null);
        }
        JkWireModel model = model(id, new File(projectPath), listener);
        List<String> added = JkSdkTable.ensure(model.sdkEntries);
        for (String name : added) listener.onTaskOutput(id, "registered SDK " + name + "\n", true);
        listener.onStatusChange(new ExternalSystemTaskNotificationEvent(id, "Building project structure"));
        return JkProjectGraph.build(model, JkSourceRoots::of);
    }

    private JkWireModel model(ExternalSystemTaskId id, File dir, ExternalSystemTaskNotificationListener listener) {
        listener.onStatusChange(new ExternalSystemTaskNotificationEvent(id, "Resolving JumpKick model"));
        listener.onTaskOutput(id, JkBin.path() + " " + String.join(" ", PRINT_MODEL) + "\n", true);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        running.put(id, indicator);
        JkCliRunner.Result run;
        try {
            run = JkCliRunner.runCapture(
                    dir, PRINT_MODEL, indicator, line -> listener.onTaskOutput(id, line + "\n", false));
        } catch (Exception e) {
            throw new ExternalSystemException(
                    "Failed to start '" + JkBin.path() + "': " + e.getMessage()
                            + " — install jk and put it on PATH, or set JK_BIN",
                    e);
        } finally {
            running.remove(id);
        }
        if (indicator.isCanceled()) throw new ExternalSystemException("JumpKick sync cancelled");
        if (!run.ok()) {
            throw new ExternalSystemException(JkCliLines.firstErrorLine(
                    run.stderr(), run.stdout(), "jk ide --print-model failed (exit " + run.exitCode() + ")"));
        }
        JkWireModel model;
        try {
            model = JkWireModel.parse(run.stdout());
        } catch (IllegalArgumentException e) {
            throw new ExternalSystemException("jk ide --print-model printed no model: " + e.getMessage(), e);
        }
        if (model.error != null) throw new ExternalSystemException(model.error);
        return model;
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
