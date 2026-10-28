// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: CodeGraph.between is renamed edgesBetween. */
class EdgesBetweenAcceptanceTest {

    @Test
    void betweenIsGone() {
        assertThat(Arrays.stream(CodeGraph.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("between");
    }

    @Test
    void edgesBetweenDoesWhatBetweenDid() {
        GraphNode a = new GraphNode("a", "A", "code", "A.java", "L1");
        GraphNode b = new GraphNode("b", "B", "code", "B.java", "L1");
        GraphEdge e = GraphEdge.of("a", "b", Relations.CALLS, Confidence.EXTRACTED, "A.java", 2);
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(List.of(a, b), List.of(e))));
        assertThat(g.edgesBetween("a", "b")).containsExactly(e);
        assertThat(g.edgesBetween("b", "a")).containsExactly(e);
    }
}
