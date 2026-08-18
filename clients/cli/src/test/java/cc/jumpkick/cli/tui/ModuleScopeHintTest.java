// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleScopeHintTest {

    @Test
    void markup_styles_a_single_project_name() {
        assertThat(ModuleScopeHint.markup("building", List.of("jk-cli")))
                .isEqualTo("[dark-gray]…building module jk-cli…[/]");
    }

    @Test
    void markup_pluralizes_and_joins_several_names() {
        assertThat(ModuleScopeHint.markup("building", List.of("jk-engine", "jk-cli")))
                .isEqualTo("[dark-gray]…building modules jk-engine, jk-cli…[/]");
    }

    @Test
    void line_has_a_leading_space() {
        String line = ModuleScopeHint.line("building", List.of("jk-cli"));
        assertThat(TestAnsi.strip(line)).isEqualTo(" …building module jk-cli…");
    }

    @Test
    void apply_sets_the_live_caption_above_the_wedge() {
        var cm = JkManager.plan(
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), "Build", false);
        cm.setModuleScopeHint("building", List.of("jk-cli"));
        var lines = cm.renderBuildPlanLines(120, 0);
        assertThat(TestAnsi.strip(lines.get(0))).isEqualTo(" …building module jk-cli…");
        assertThat(TestAnsi.strip(lines.get(1))).contains("Build");
    }

    @Test
    void empty_names_are_a_no_op() {
        assertThat(ModuleScopeHint.markup("building", List.of())).isEmpty();
        assertThat(ModuleScopeHint.line("building", List.of())).isEmpty();
        assertThat(ModuleScopeHint.namesFrom(null)).isEmpty();
    }
}
