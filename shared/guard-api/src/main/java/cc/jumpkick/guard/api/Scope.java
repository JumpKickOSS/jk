// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

/** What a {@link GuardSuite} reads, which decides its lane and cache key. */
public enum Scope {
    /** One module's facts; runs in {@code guard:<module>} keyed on that module's index. */
    MODULE,
    /** Every module's facts (and the tree, when {@link Text} is injected); runs in {@code guard-workspace}. */
    WORKSPACE
}
