// SPDX-License-Identifier: Apache-2.0
/**
 * The planner core of the build runtime: module planners, lock and resolve pipelines, and the
 * services every verb drives. The scheduler and its live units sit below it in {@code
 * runtime.base}; the workspace phases that drive it sit above in {@code runtime.workspace}.
 */
@NullMarked
package cc.jumpkick.runtime;

import org.jspecify.annotations.NullMarked;
