// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

/**
 * Terminal basket for a workspace build-family request. Same orchestrator
 * ({@link WorkspaceExecute}); only the plan terminal and optional module cone change.
 */
public enum WorkspaceTarget {
    /** {@code jk build}: package (+ declared tails). */
    PACKAGE,
    /** {@code jk test}: stop at {@code run-tests}. */
    TEST,
    /** {@code jk compile}: compile + stamps only. */
    COMPILE,
    /** {@code jk native}: selected modules terminal {@code native-image}; prereqs package. */
    NATIVE,
    /** {@code jk image}: selected module terminal {@code write-image}; prereqs package. */
    IMAGE;

    public boolean testOnly() {
        return this == TEST;
    }

    public boolean compileOnly() {
        return this == COMPILE;
    }
}
