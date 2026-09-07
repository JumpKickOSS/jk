// SPDX-License-Identifier: Apache-2.0
/**
 * ArchUnit inside a guard test, unchanged: {@link cc.jumpkick.guard.api.archunit.JkArchUnit} turns
 * an {@code ArchRule}'s evaluation into violations with line-independent fingerprints, and
 * {@link cc.jumpkick.guard.api.archunit.JkViolationStore} lets a {@code FreezingArchRule} read
 * {@code jk-guards-baseline.toml} instead of its own store — reads only; the baseline is written by
 * {@code jk guard freeze}, never by a run. ArchUnit itself is the project's dependency.
 */
@NullMarked
package cc.jumpkick.guard.api.archunit;

import org.jspecify.annotations.NullMarked;
