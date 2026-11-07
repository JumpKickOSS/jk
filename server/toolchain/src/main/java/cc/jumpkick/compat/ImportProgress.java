// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

/**
 * What one stage of {@code jk import} reports while it runs: milestone lines a transcript keeps,
 * the stage's current detail, and its units of work as they become known and finish.
 */
public interface ImportProgress {

    /** Reports nothing. */
    ImportProgress NONE = new ImportProgress() {};

    /** A line worth keeping: a tool provisioned, the build being evaluated. */
    default void milestone(String line) {}

    /** What the stage is doing now; replaces the previous detail. */
    default void detail(String text) {}

    /** The stage has {@code units} more units of work than it said so far. */
    default void grow(int units) {}

    /** {@code units} units of work finished. */
    default void advance(int units) {}
}
