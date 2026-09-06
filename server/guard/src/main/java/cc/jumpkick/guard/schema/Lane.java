// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

/** The built-in task a rule runs in; the read set the lane is keyed on follows from it. */
public enum Lane {
    /** Root, every build, at plan time. */
    MODEL,
    /** After that module's compile, every build. */
    MODULE,
    /** Root, after every compile, over all facts indexes. */
    WORKSPACE,
    /** Root, {@code --gate} and {@code jk guard}: the tree scan. */
    TREE,
    /** Root, after package / native / test-with-coverage. */
    OUTPUT,
    /** Never a build task: the commit-msg hook. */
    HOOK
}
