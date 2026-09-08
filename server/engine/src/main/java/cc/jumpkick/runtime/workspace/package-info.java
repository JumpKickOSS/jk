// SPDX-License-Identifier: Apache-2.0
/**
 * The top of the engine's build runtime: the workspace phases, the build service, the ETA and the
 * plan builders that compose the planner core below them. Everything here reads
 * {@code cc.jumpkick.runtime} and {@code runtime.base}; nothing below reads this package.
 */
@NullMarked
package cc.jumpkick.runtime.workspace;

import org.jspecify.annotations.NullMarked;
