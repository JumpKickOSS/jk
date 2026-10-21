// SPDX-License-Identifier: Apache-2.0
/**
 * The engine's half of {@code jk graph}: keep each module's class facts index current, then fork the
 * {@code jk-graph} worker, which owns the graph and its memory, and relay its progress and answer.
 */
@NullMarked
package cc.jumpkick.engine.graph;

import org.jspecify.annotations.NullMarked;
