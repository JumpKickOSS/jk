// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Per-machine external-system bookkeeping (sync types, recent tasks); lives in the IDE cache. */
@State(name = "JumpKickLocalSettings", storages = @Storage(StoragePathMacros.CACHE_FILE))
public final class JkLocalSettings extends AbstractExternalSystemLocalSettings<JkLocalSettings.Persisted>
        implements PersistentStateComponent<JkLocalSettings.Persisted> {

    public JkLocalSettings(@NotNull Project project) {
        super(JkSystem.ID, project, new Persisted());
    }

    public static JkLocalSettings getInstance(@NotNull Project project) {
        return project.getService(JkLocalSettings.class);
    }

    @Override
    public void loadState(@NotNull Persisted state) {
        super.loadState(state);
    }

    /** The platform's state shape, named so the annotation and the class do not collide. */
    public static final class Persisted extends AbstractExternalSystemLocalSettings.State {}
}
