// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.List;

/** What one extractor found in one file: merged into the graph by {@link CodeGraph#merge}. */
public record Fragment(List<GraphNode> nodes, List<GraphEdge> edges) {

    public static final Fragment EMPTY = new Fragment(List.of(), List.of());

    public Fragment {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
