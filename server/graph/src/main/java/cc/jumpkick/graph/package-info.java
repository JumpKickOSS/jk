// SPDX-License-Identifier: Apache-2.0
/**
 * Builds a workspace's code graph: one walk of the tree, per-file extraction reused across runs by
 * content, compiled class facts mapped to calls and references, then linked, clustered and written
 * to {@code target/graph/}. See {@code docs/user/graph.md}.
 */
@NullMarked
package cc.jumpkick.graph;

import org.jspecify.annotations.NullMarked;
