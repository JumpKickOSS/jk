// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeTest {

    private static Tree sampleGraph() {
        return new Tree("Build Graph")
                .root(Tree.node(Icon.pulse(), RichText.plain("cc.jumpkick:jk"))
                        .child(Tree.node(Pill.of("Fully Cached"), "2 modules are fresh")
                                .body(RichText.plain("+ jk-core, + jk-web,"), RichText.plain("+ jk-cli")))
                        .child(Tree.node(Pill.of("Rebuild"), "2 modules are dirty")
                                .child(Tree.node(Pill.branded("jk-engine"))
                                        .body(RichText.plain("[ ] Compile 10 sources > + Test")))
                                .child(Tree.node(Pill.branded("jk-cli"))
                                        .body(RichText.plain("[ ] Compile > [ ] Native")))));
    }

    @Test
    void plain_build_graph_has_wedge_root_spacer_pills_and_hanging_body() {
        List<String> lines = sampleGraph().render(RenderContext.current().withAnsi(false));
        assertThat(lines.get(0)).isEqualTo(" = Build Graph >");
        assertThat(lines.get(1)).isEqualTo(" * cc.jumpkick:jk");
        assertThat(lines.get(2)).isEqualTo(" |");
        assertThat(lines.get(3)).isEqualTo(" +-[Fully Cached] 2 modules are fresh");
        assertThat(lines.get(4)).isEqualTo(" |  `- + jk-core, + jk-web,");
        assertThat(lines.get(5)).isEqualTo(" |     + jk-cli");
        assertThat(lines.get(6)).isEqualTo(" `-[Rebuild] 2 modules are dirty");
        assertThat(lines.get(7)).isEqualTo("    |");
        assertThat(lines.get(8)).isEqualTo("    +-[jk-engine]");
        assertThat(lines.get(9)).isEqualTo("    |  `- [ ] Compile 10 sources > + Test");
        assertThat(lines.get(10)).isEqualTo("    `-[jk-cli]");
        assertThat(lines.get(11)).isEqualTo("       `- [ ] Compile > [ ] Native");
    }

    @Test
    void body_skips_spacer_children_insert_one() {
        Tree onlyBody = new Tree("T")
                .root(Tree.node(Icon.pulse(), "root")
                        .child(Tree.node(Pill.of("Leaf"), "note").body(RichText.plain("names"))));
        List<String> bodyLines = onlyBody.render(RenderContext.current().withAnsi(false));
        assertThat(bodyLines).containsExactly(" = T >", " * root", " |", " `-[Leaf] note", "    `- names");

        Tree branched = new Tree("T")
                .root(Tree.node(Icon.pulse(), "root")
                        .child(Tree.node(Pill.branded("mod")).child(Tree.node("step"))));
        List<String> branchLines = branched.render(RenderContext.current().withAnsi(false));
        assertThat(branchLines).containsExactly(" = T >", " * root", " |", " `-[mod]", "    |", "    `- step");
    }

    @Test
    void jobs_shape_uses_each_gap_and_no_root() {
        List<String> lines = new Tree("Build Jobs")
                .gap(Tree.Gap.EACH)
                .child(Tree.node(Pill.of("A"), "one"))
                .child(Tree.node(Pill.of("B"), "two"))
                .render(RenderContext.current().withAnsi(false));
        assertThat(lines).containsExactly(" = Build Jobs >", " |", " +-[A] one", " |", " `-[B] two");
    }

    @Test
    void untitled_none_is_compact() {
        List<String> lines = Tree.untitled()
                .gap(Tree.Gap.NONE)
                .child(Tree.node("running").body(RichText.plain("boom")).bodyFit(Tree.BodyFit.INDENT))
                .child(Tree.node("other"))
                .render(RenderContext.current().withAnsi(false));
        assertThat(lines).containsExactly(" +- running", " |  boom", " `- other");
    }

    @Test
    void rail_body_is_scope_annotation() {
        List<String> lines = new Tree("Dependencies Tree")
                .gap(Tree.Gap.NONE)
                .root(Tree.node(Icon.pulse(), "g:a:1")
                        .bodyFit(Tree.BodyFit.RAIL)
                        .body(RichText.plain("· Scopes: main"))
                        .child(Tree.node(Pill.of("main")).flush(true).child(Tree.node("com.foo:bar:1.0"))))
                .render(RenderContext.current().withAnsi(false));
        assertThat(lines)
                .containsExactly(
                        " = Dependencies Tree >",
                        " * g:a:1",
                        " | - Scopes: main",
                        " `-[main]",
                        "    `- com.foo:bar:1.0");
    }

    @Test
    void forest_recovers_painted_lines() {
        List<Tree.Node> nodes =
                Tree.forest(List.of("+- com.foo:root:1.0", "|  `- com.foo:leaf:1.0", "`- com.foo:other:1.0"));
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).label().plainText()).isEqualTo("com.foo:root:1.0");
        assertThat(nodes.get(0).children()).hasSize(1);
        assertThat(nodes.get(0).children().get(0).label().plainText()).isEqualTo("com.foo:leaf:1.0");
        assertThat(nodes.get(1).label().plainText()).isEqualTo("com.foo:other:1.0");
    }

    @Test
    void forest_recovers_stacked_rails_for_transitive_deps() {
        List<Tree.Node> nodes = Tree.forest(List.of(
                "+-[main]",
                "|  +- com.foo:root:1.0",
                "|  |  `- com.foo:mid:1.0",
                "|  |     `- com.foo:leaf:1.0",
                "|  `- com.foo:other:1.0"));
        assertThat(nodes).hasSize(1);
        Tree.Node main = nodes.get(0);
        assertThat(main.children()).hasSize(2);
        Tree.Node root = main.children().get(0);
        assertThat(root.label().plainText()).isEqualTo("com.foo:root:1.0");
        assertThat(root.children()).hasSize(1);
        assertThat(root.children().get(0).label().plainText()).isEqualTo("com.foo:mid:1.0");
        assertThat(root.children().get(0).children()).hasSize(1);
        assertThat(root.children().get(0).children().get(0).label().plainText()).isEqualTo("com.foo:leaf:1.0");
    }

    @Test
    void nerd_pills_use_half_circles_ansi_does_not() {
        if (!Theme.active().isAnsi()) return;
        String nerd = String.join(
                "\n",
                sampleGraph().render(RenderContext.current().withAnsi(true).withNerd(true)));
        String ansi = String.join(
                "\n",
                sampleGraph().render(RenderContext.current().withAnsi(true).withNerd(false)));
        String nerdPlain = TestAnsi.strip(nerd);
        String ansiPlain = TestAnsi.strip(ansi);
        assertThat(nerd).contains(Glyphs.PILL_LEFT_NERD).contains(Glyphs.PILL_RIGHT_NERD);
        assertThat(ansi).doesNotContain(Glyphs.PILL_LEFT_NERD);
        assertThat(nerdPlain).contains("Fully Cached").contains("Rebuild").contains("jk-engine");
        assertThat(ansiPlain).contains("Fully Cached").contains("jk-engine");
        assertThat(nerdPlain).doesNotContain("[01]").doesNotContain("01");
    }
}
