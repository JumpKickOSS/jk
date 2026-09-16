// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings;

/** One linked JumpKick project: the directory holding its {@code jk.toml}. */
public final class JkProjectSettings extends ExternalProjectSettings {

    public JkProjectSettings() {}

    public JkProjectSettings(String externalProjectPath) {
        setExternalProjectPath(externalProjectPath);
    }

    @Override
    public JkProjectSettings clone() {
        JkProjectSettings copy = new JkProjectSettings();
        copyTo(copy);
        return copy;
    }
}
