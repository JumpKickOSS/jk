// SPDX-License-Identifier: Apache-2.0
/**
 * What runs inside the forked JVM: the views a {@code @GuardSuite} receives, built from the files
 * jk names in {@code -Djk.guard.config}, and the report each guard appends for the engine to judge.
 * Nothing here reads bytecode or resolves anything — the facts index, the model snapshot and the
 * source roots are inputs the engine prepared.
 */
@NullMarked
package cc.jumpkick.guard.api.runtime;

import org.jspecify.annotations.NullMarked;
