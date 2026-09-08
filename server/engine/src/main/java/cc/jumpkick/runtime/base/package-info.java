// SPDX-License-Identifier: Apache-2.0
/**
 * What the planner reads and nothing here reads back: metrics and priors, compile and tool
 * support, lock primitives, identities, the small plan builders that stand alone. No class in this
 * package names a class in {@code cc.jumpkick.runtime} or {@code runtime.workspace}, and none imports
 * {@code task}, {@code compile}, {@code test} or {@code git} — that is what keeps it out of the
 * planner core's cycle.
 */
@NullMarked
package cc.jumpkick.runtime.base;

import org.jspecify.annotations.NullMarked;
