// SPDX-License-Identifier: Apache-2.0
/**
 * The code graph worker: one walk of the tree, per-file extraction and compiled class facts reused
 * from the action cache, then linked, clustered and written to {@code target/graph/}. See {@code
 * docs/user/graph.md}.
 */
@NullMarked
package cc.jumpkick.graph;

import org.jspecify.annotations.NullMarked;
