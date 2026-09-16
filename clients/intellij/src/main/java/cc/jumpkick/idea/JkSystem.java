// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.model.ProjectSystemId;

/** The external-system identity IntelliJ files every JumpKick module, library and task under. */
public final class JkSystem {

    public static final ProjectSystemId ID = new ProjectSystemId("JUMPKICK", "JumpKick");

    /** Module type of every resolved module. */
    static final String JAVA_MODULE_TYPE = "JAVA_MODULE";

    private JkSystem() {}
}
