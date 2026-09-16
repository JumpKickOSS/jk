// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.configurations.SimpleJavaParameters;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Registers JumpKick as an IntelliJ external system: settings, the in-process
 * {@link JkProjectResolver} and the {@link JkTaskManager}. The registry key
 * {@code JUMPKICK.system.in.process} (declared in {@code plugin.xml}) keeps both inside the IDE
 * process; they only ever spawn the {@code jk} CLI.
 */
public final class JkExternalSystemManager
        implements ExternalSystemManager<
                JkProjectSettings, JkSettingsListener, JkSettings, JkLocalSettings, JkExecutionSettings> {

    @Override
    public @NotNull ProjectSystemId getSystemId() {
        return JkSystem.ID;
    }

    @Override
    public @NotNull Function<Project, JkSettings> getSettingsProvider() {
        return JkSettings::getInstance;
    }

    @Override
    public @NotNull Function<Project, JkLocalSettings> getLocalSettingsProvider() {
        return JkLocalSettings::getInstance;
    }

    @Override
    public @NotNull Function<Pair<Project, String>, JkExecutionSettings> getExecutionSettingsProvider() {
        return pair -> new JkExecutionSettings(JkBin.path());
    }

    @Override
    public @NotNull Class<? extends JkProjectResolver> getProjectResolverClass() {
        return JkProjectResolver.class;
    }

    @Override
    public @NotNull Class<? extends JkTaskManager> getTaskManagerClass() {
        return JkTaskManager.class;
    }

    @Override
    public @NotNull FileChooserDescriptor getExternalProjectDescriptor() {
        return new FileChooserDescriptor(true, true, false, false, false, false)
                .withFileFilter(file -> "jk.toml".equals(file.getName()));
    }

    @Override
    public void enhanceRemoteProcessing(@NotNull SimpleJavaParameters parameters) {
        throw new UnsupportedOperationException("JumpKick resolves in-process; nothing runs in a remote JVM");
    }
}
