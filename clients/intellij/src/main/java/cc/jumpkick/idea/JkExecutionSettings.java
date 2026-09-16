// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings;

/** What a resolve or task run needs beyond the project path: where the {@code jk} binary is. */
public final class JkExecutionSettings extends ExternalSystemExecutionSettings {

    private static final long serialVersionUID = 1L;

    private final String jkBin;

    public JkExecutionSettings(String jkBin) {
        this.jkBin = jkBin;
    }

    public String jkBin() {
        return jkBin;
    }
}
