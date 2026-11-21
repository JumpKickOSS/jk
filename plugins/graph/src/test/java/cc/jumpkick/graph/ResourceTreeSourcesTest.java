// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A Java file kept in a resource tree is no source a build compiles, in a simple-layout root module
 * as in a member, and a source in a package named {@code resources} still is one.
 */
class ResourceTreeSourcesTest {

    private static CodeGraph graph() {
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(
                List.of(
                        new GraphNode("a", "A.java", "code", "src/a/A.java", "L1"),
                        new GraphNode("b", "B.java", "code", "src/a/resources/B.java", "L1"),
                        new GraphNode("t", "Tpl.java", "code", "resources/a/Tpl.java", "L1"),
                        new GraphNode("l", "L.java", "code", "lib/src/l/resources/L.java", "L1"),
                        new GraphNode("u", "Tpl.java", "code", "lib/resources/l/Tpl.java", "L1")),
                List.of())));
        g.modules(new Modules(List.of(new Modules.Module("", true), new Modules.Module("lib/", true))));
        return g;
    }

    @Test
    void theRenameProofReadsSourcesNotResourceTrees() {
        assertThat(RenameProof.javaFiles(graph()))
                .containsExactly("lib/src/l/resources/L.java", "src/a/A.java", "src/a/resources/B.java");
    }

    @Test
    void anUnbuiltModuleIsOneWithSourcesNotOnlyResources() {
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(
                List.of(
                        new GraphNode("a", "A.java", "code", "lib/src/l/resources/A.java", "L1"),
                        new GraphNode("t", "Tpl.java", "code", "resources/a/Tpl.java", "L1")),
                List.of())));
        g.modules(graph().modules());
        assertThat(RenameReach.unbuilt(g, List.of(), null)).containsExactly("lib/ main");
    }
}
