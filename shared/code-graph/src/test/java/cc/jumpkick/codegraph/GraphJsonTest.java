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

    /**
     * The committed form round-trips every field: defaults and the file itself left out, ids under
     * the first node's written from {@code ~}, a node or edge of another file kept, an overload kept.
     */
    @Test
    void aFragmentRoundTripsInTheCommittedFormAndWritesStableBytes() {
        GraphNode a =
                new GraphNode("src_a_a", "A", "code", "src/A.java", "L1", Map.of("zeta", "z", "alpha", List.of("x")));
        GraphNode b = new GraphNode("src_a_a_run", ".run()", "code", "src/A.java", "L3");
        GraphNode elsewhere = new GraphNode("other", "Other", "code", "src/Other.java", "L9");
        GraphEdge e = GraphEdge.of("src_a_a", "src_a_a_run", Relations.METHOD, Confidence.EXTRACTED, "src/A.java", 3);
        GraphEdge weighed = new GraphEdge(
                "src_a_a_run",
                "other",
                Relations.CALLS,
                Confidence.INFERRED,
                0.4,
                "src/Other.java",
                "L4",
                2.0,
                "call",
                Map.of("overloads", List.of("()V>()V")));
        Mention m = new Mention("src_a_a", Mention.Kind.CLASS, "com.acme.B", 2, Relations.REFERENCES, "field");
        Mention call = new Mention("src_a_a_run", Mention.Kind.MEMBER, "B.go", 5, Relations.CALLS, "call", "()V>(I)V");
        Fragment f = new Fragment(List.of(a, b, elsewhere), List.of(e, weighed), List.of(m, call));
        String once = MiniJson.write(GraphJson.compactTree(f, "src/A.java"));
        Fragment back = GraphJson.compactOf(MiniJson.parse(once), "src/A.java");
        assertThat(back).isEqualTo(f);
        assertThat(MiniJson.write(GraphJson.compactTree(back, "src/A.java"))).isEqualTo(once);
        assertThat(once)
                .startsWith("{\"b\":\"src_a_a\",\"n\":[[\"~\",\"A\",\"code\",\"L1\",{\"alpha\"")
                .contains("[\"~_run\",\".run()\",\"code\",\"L3\"]")
                .doesNotContain("\"src/A.java\"");
        assertThat(GraphJson.compactOf(MiniJson.parse("{}"), "x")).isEqualTo(Fragment.EMPTY);
    }

    @Test
    void aSnapshotRoundTripsTheGraph() throws IOException {
        CodeGraph g = ShopFixture.graph();
        var bytes = new ByteArrayOutputStream();
        GraphSnapshot.write(g, new GraphSnapshot.Key("x", 7, "c", "i", "abc123"), bytes);
        var loaded = Objects.requireNonNull(GraphSnapshot.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertThat(loaded.key()).isEqualTo(new GraphSnapshot.Key("x", 7, "c", "i", "abc123"));
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
        GraphSnapshot.write(ShopFixture.graph(), new GraphSnapshot.Key("x", 7, "c", "i", null), bytes);
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
