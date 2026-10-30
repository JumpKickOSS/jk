// SPDX-License-Identifier: Apache-2.0
/**
 * The code graph: symbols and the edges between them, kept as a binary snapshot and queried under
 * a token budget. Extraction lives engine- and worker-side; this package only models, merges,
 * serializes and queries. See {@code docs/user/graph.md}.
 */
@NullMarked
package cc.jumpkick.codegraph;

import org.jspecify.annotations.NullMarked;
