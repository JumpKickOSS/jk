// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphJsonTest {

    static CodeGraph shop() throws IOException {
        try (InputStream in = GraphJsonTest.class.getResourceAsStream("graphify-shop.json")) {
            return GraphJson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void readsAGraphGraphifyWrote() throws IOException {
        CodeGraph g = shop();
        assertThat(g.size()).isEqualTo(23);
        assertThat(g.edges()).hasSize(27);
        assertThat(g.node("src_main_java_com_acme_shop_orderservice_orderservice_total"))
                .satisfies(total -> {
                    assertThat(total.label()).isEqualTo(".total()");
                    assertThat(total.sourceLocation()).isEqualTo("L10");
                    assertThat(total.extra()).containsKey("_origin");
                });
        assertThat(g.community("src_main_java_com_acme_shop_orderservice_orderservice"))
                .isZero();
        assertThat(g.communityName(0)).isEqualTo("OrderService");
    }

    @Test
    void writeIsStableAndRoundTrips() throws IOException {
        CodeGraph g = shop();
        String once = GraphJson.write(g, "jk test", "abc123");
        String twice = GraphJson.write(GraphJson.read(once), "jk test", "abc123");
        assertThat(twice).isEqualTo(once);
        assertThat(once).startsWith("{\"directed\":false,\"multigraph\":false,\"graph\":{\"schema_version\":1,");
        assertThat(once).contains("{\"id\":\"docs_notes\",\"label\":\"notes.md\",");
        assertThat(once).contains("\"built_at_commit\":\"abc123\"");
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
