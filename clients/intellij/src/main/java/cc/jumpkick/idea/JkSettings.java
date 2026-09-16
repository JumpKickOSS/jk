// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.XCollection;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The linked JumpKick projects of an IDE project, persisted in {@code .idea/jumpkick.xml}. */
@State(name = "JumpKickSettings", storages = @Storage("jumpkick.xml"))
public final class JkSettings extends AbstractExternalSystemSettings<JkSettings, JkProjectSettings, JkSettingsListener>
        implements PersistentStateComponent<JkSettings.Persisted> {

    public JkSettings(@NotNull Project project) {
        super(JkSettingsListener.TOPIC, project);
    }

    public static JkSettings getInstance(@NotNull Project project) {
        return project.getService(JkSettings.class);
    }

    @Override
    public void subscribe(
            @NotNull ExternalSystemSettingsListener<JkProjectSettings> listener, @NotNull Disposable parentDisposable) {
        doSubscribe(new Delegating(listener), parentDisposable);
    }

    @Override
    public void subscribe(@NotNull ExternalSystemSettingsListener<JkProjectSettings> listener) {
        subscribe(listener, this);
    }

    @Override
    protected void copyExtraSettingsFrom(@NotNull JkSettings settings) {}

    @Override
    protected void checkSettings(@NotNull JkProjectSettings old, @NotNull JkProjectSettings current) {}

    @Override
    public @Nullable Persisted getState() {
        Persisted state = new Persisted();
        fillState(state);
        return state;
    }

    @Override
    public void loadState(@NotNull Persisted state) {
        super.loadState(state);
    }

    /** A generic settings listener seen through the JumpKick topic's interface. */
    private static final class Delegating implements JkSettingsListener {

        private final ExternalSystemSettingsListener<JkProjectSettings> delegate;

        Delegating(ExternalSystemSettingsListener<JkProjectSettings> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onProjectRenamed(@NotNull String oldName, @NotNull String newName) {
            delegate.onProjectRenamed(oldName, newName);
        }

        @Override
        public void onProjectsLoaded(@NotNull Collection<JkProjectSettings> settings) {
            delegate.onProjectsLoaded(settings);
        }

        @Override
        public void onProjectsLinked(@NotNull Collection<JkProjectSettings> settings) {
            delegate.onProjectsLinked(settings);
        }

        @Override
        public void onProjectsUnlinked(@NotNull Set<String> linkedProjectPaths) {
            delegate.onProjectsUnlinked(linkedProjectPaths);
        }

        @Override
        public void onBulkChangeStart() {
            delegate.onBulkChangeStart();
        }

        @Override
        public void onBulkChangeEnd() {
            delegate.onBulkChangeEnd();
        }
    }

    /** XML shape: the set of linked project settings. */
    public static final class Persisted implements AbstractExternalSystemSettings.State<JkProjectSettings> {

        private final Set<JkProjectSettings> projects = new TreeSet<>();

        @XCollection(elementTypes = JkProjectSettings.class)
        @Override
        public Set<JkProjectSettings> getLinkedExternalProjectsSettings() {
            return projects;
        }

        @Override
        public void setLinkedExternalProjectsSettings(@Nullable Set<JkProjectSettings> settings) {
            if (settings != null) projects.addAll(settings);
        }
    }
}
