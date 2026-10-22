// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesForTest {

    private static final Path ROOT = Path.of("/w");

    private static CodeGraph graph() {
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(
                List.of(
                        new GraphNode("f", "Shop.java", "code", "app/src/main/java/a/Shop.java", "L1"),
                        new GraphNode(
                                "n", "NOTE: totals are in cents", "rationale", "app/src/main/java/a/Shop.java", "L9")),
                List.of())));
        g.modules(new Modules(List.of(new Modules.Module("", false), new Modules.Module("app/", false))));
        return g;
    }

    private static final List<String> ROWS = List.of("""
            {"id":"no-system-out","kind":"forbid","why":"stdout is not a log sink","instead":"the Logger","set":"main","files":[],"languages":[]}""", """
            {"id":"tests-tagged","kind":"tiers","why":"slow tests are tagged","set":"test","files":[],"languages":[]}""", """
            {"id":"no-todo","kind":"text","why":"no TODO in Kotlin","set":"main","files":[],"languages":["kotlin"]}""", """
            {"id":"readme-table","kind":"generated","why":"the table is rendered","set":"main","files":[],"languages":[],"into":"README.md"}""");

    @Test
    void aMainJavaFileGetsItsBytecodeRulesAndNotes() {
        String out = RulesFor.answer(graph(), ROOT, "app/src/main/java/a/Shop.java", ROWS);
        assertThat(out)
                .startsWith("Rules for app/src/main/java/a/Shop.java (module app/, main):")
                .contains("no-system-out [forbid] stdout is not a log sink Instead: the Logger")
                .doesNotContain("tests-tagged")
                .doesNotContain("no-todo")
                .doesNotContain("readme-table")
                .contains("Notes in this file:\n  L9 NOTE: totals are in cents");
    }

    @Test
    void aGeneratedBlocksTargetNamesItsRuleAndAnUngovernedFileSaysSo() {
        assertThat(RulesFor.answer(graph(), ROOT, "/w/README.md", ROWS)).contains("readme-table [generated]");
        assertThat(RulesFor.answer(graph(), ROOT, "docs/x.md", List.of()))
                .isEqualTo("No guard rules or notes govern docs/x.md.");
    }
}
