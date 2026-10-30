// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.jsonl.MiniJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class GraphJsonTest {

    @Test
    void aFragmentRoundTripsAndWritesStableBytes() {
        GraphNode a = new GraphNode("a", "A", "code", "A.java", "L1", Map.of("zeta", "z", "alpha", "x"));
        GraphNode b = new GraphNode("b", ".run()", "code", "A.java", "L3");
        GraphEdge e = GraphEdge.of("a", "b", Relations.METHOD, Confidence.EXTRACTED, "A.java", 3);
        Mention m = new Mention("a", Mention.Kind.CLASS, "com.acme.B", 2, Relations.REFERENCES, "field");
        Fragment f = new Fragment(List.of(a, b), List.of(e), List.of(m));
        String once = MiniJson.write(GraphJson.fragmentTree(f));
        Fragment back = GraphJson.fragmentOf(MiniJson.parse(once));
        assertThat(back).isEqualTo(f);
        assertThat(MiniJson.write(GraphJson.fragmentTree(back))).isEqualTo(once);
        assertThat(once).contains("{\"id\":\"a\",\"label\":\"A\",\"alpha\":\"x\",");
    }

    @Test
    void aSnapshotRoundTripsTheGraph() throws IOException {
        CodeGraph g = ShopFixture.graph();
        var bytes = new ByteArrayOutputStream();
        GraphSnapshot.write(g, new GraphSnapshot.Key("x", 7, "c", "abc123"), bytes);
        var loaded = Objects.requireNonNull(GraphSnapshot.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertThat(loaded.key()).isEqualTo(new GraphSnapshot.Key("x", 7, "c", "abc123"));
        assertThat(loaded.graph().nodes())
                .extracting(GraphNode::id)
                .containsExactlyInAnyOrderElementsOf(
                        g.nodes().stream().map(GraphNode::id).toList());
        assertThat(loaded.graph().edges())
                .extracting(GraphEdge::source, GraphEdge::target, GraphEdge::relation, GraphEdge::sourceLocation)
                .containsExactlyInAnyOrderElementsOf(g.edges().stream()
                        .map(e -> tuple(e.source(), e.target(), e.relation(), e.sourceLocation()))
                        .toList());
    }

    /** A snapshot of another layout, or cut short, reads as none, so the graph is built again. */
    @Test
    void aSnapshotThatDoesNotParseIsAbsent() throws IOException {
        var bytes = new ByteArrayOutputStream();
        GraphSnapshot.write(ShopFixture.graph(), new GraphSnapshot.Key("x", 7, "c", null), bytes);
        byte[] whole = bytes.toByteArray();
        byte[] longer = Arrays.copyOf(whole, whole.length + 4);
        byte[] shorter = Arrays.copyOf(whole, whole.length / 2);
        assertThat(GraphSnapshot.read(new ByteArrayInputStream(longer))).isNull();
        assertThat(GraphSnapshot.read(new ByteArrayInputStream(shorter))).isNull();
        assertThat(GraphSnapshot.read(new ByteArrayInputStream(new byte[] {1, 2})))
                .isNull();
    }

    @Test
    void mergeDedupesNodesAndEdgesAndDropsDanglingEdges() {
        GraphNode stub = new GraphNode("list", "list", "concept", "", "");
        GraphNode real = new GraphNode("list", "List", "code", "List.java", "L1");
        GraphNode a = new GraphNode("a", "A", "code", "A.java", "L1");
        GraphEdge e = GraphEdge.of("a", "list", Relations.REFERENCES, Confidence.EXTRACTED, "A.java", 2);
        GraphEdge dangling = GraphEdge.of("a", "missing", Relations.CALLS, Confidence.EXTRACTED, "A.java", 3);
        CodeGraph g = CodeGraph.merge(
                List.of(new Fragment(List.of(a, stub), List.of(e, dangling)), new Fragment(List.of(real), List.of(e))));
        assertThat(g.node("list")).isEqualTo(real);
        assertThat(g.edges()).containsExactly(e);
    }
}
