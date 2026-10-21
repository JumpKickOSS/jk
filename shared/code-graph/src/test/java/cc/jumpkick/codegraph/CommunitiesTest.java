// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommunitiesTest {

    /** Two five-node cliques joined by one edge. */
    static CodeGraph barbell() {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (String side : List.of("a", "b")) {
            for (int i = 0; i < 5; i++)
                nodes.add(new GraphNode(side + i, side.toUpperCase() + i, "code", side + ".java", "L1"));
            for (int i = 0; i < 5; i++) {
                for (int j = i + 1; j < 5; j++) {
                    edges.add(
                            GraphEdge.of(side + i, side + j, Relations.CALLS, Confidence.EXTRACTED, side + ".java", 1));
                }
            }
        }
        edges.add(GraphEdge.of("a0", "b0", Relations.CALLS, Confidence.INFERRED, "a.java", 1));
        return CodeGraph.merge(List.of(new Fragment(nodes, edges)));
    }

    @Test
    void separatesCliquesDeterministically() {
        CodeGraph g = barbell();
        Communities.assign(g);
        assertThat(g.communities()).hasSize(2);
        assertThat(g.community("a1")).isEqualTo(g.community("a4"));
        assertThat(g.community("a1")).isNotEqualTo(g.community("b1"));
        assertThat(g.communityName(g.community("a1"))).isEqualTo("A0");
        CodeGraph again = barbell();
        Communities.assign(again);
        assertThat(again.community("b3")).isEqualTo(g.community("b3"));
    }

    @Test
    void reportNamesGodNodesAndCommunities() {
        CodeGraph g = barbell();
        Communities.assign(g);
        String md = GraphReport.write(g, null);
        assertThat(md).contains("- 10 nodes, 21 edges, 2 communities");
        assertThat(md).contains("1. `A0` — 5 edges");
    }

    @Test
    void importRingsAreReportedAsCycles() {
        List<GraphNode> nodes = new ArrayList<>();
        for (String f : List.of("a.ts", "b.ts", "c.ts", "d.ts"))
            nodes.add(new GraphNode(GraphIds.file(f), f, "code", f, "L1"));
        List<GraphEdge> edges = List.of(
                GraphEdge.of("a", "b", Relations.IMPORTS_FROM, Confidence.EXTRACTED, "a.ts", 1),
                GraphEdge.of("b", "c", Relations.IMPORTS_FROM, Confidence.EXTRACTED, "b.ts", 1),
                GraphEdge.of("c", "a", Relations.IMPORTS_FROM, Confidence.EXTRACTED, "c.ts", 1),
                GraphEdge.of("c", "d", Relations.IMPORTS_FROM, Confidence.EXTRACTED, "c.ts", 2));
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(nodes, edges)));
        assertThat(GraphAnalysis.importCycles(g, 10)).containsExactly(List.of("a.ts", "b.ts", "c.ts"));
        assertThat(GraphReport.write(g, null))
                .contains("## Import cycles")
                .contains("- 3 files: `a.ts`, `b.ts`, `c.ts`");
    }
}
