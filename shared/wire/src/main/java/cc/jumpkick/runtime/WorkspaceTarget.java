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
    IMAGE,
    /**
     * {@code jk install}: package (+ declared tails) then {@code cache-install} the thin jar.
     * Fat/minified and native PATH placement stay client-side.
     */
    INSTALL;

    public boolean testOnly() {
        return this == TEST;
    }

    public boolean compileOnly() {
        return this == COMPILE;
    }
}
