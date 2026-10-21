// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.List;

/**
 * What one extractor found in one file: its nodes, the edges it could resolve alone, and the
 * mentions only the whole graph can resolve. Merged by {@link CodeGraph#merge}; mentions are
 * resolved by {@link Linker}.
 */
public record Fragment(List<GraphNode> nodes, List<GraphEdge> edges, List<Mention> mentions) {

    public static final Fragment EMPTY = new Fragment(List.of(), List.of(), List.of());

    public Fragment {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        mentions = List.copyOf(mentions);
    }

    public Fragment(List<GraphNode> nodes, List<GraphEdge> edges) {
        this(nodes, edges, List.of());
    }
}
