// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceCodeTest {

    @Test
    void java_lines_preserve_source_text() {
        var code = SourceCode.java("class Foo {\n  void bar() {}\n}");
        assertThat(code.lines()).hasSize(3);
        assertThat(code.lines().get(0).plainText()).isEqualTo("class Foo {");
        assertThat(code.lines().get(1).plainText()).isEqualTo("  void bar() {}");
    }

    @Test
    void kotlin_and_groovy_round_trip() {
        assertThat(SourceCode.kotlin("fun main() {}").lines().getFirst().plainText())
                .isEqualTo("fun main() {}");
        assertThat(SourceCode.groovy("def x = 1").lines().getFirst().plainText())
                .isEqualTo("def x = 1");
    }

    @Test
    void ansi_highlights_keywords_when_color_is_on() {
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) return;
        String painted =
                SourceCode.java("class Foo {}").render(RenderContext.current()).getFirst();
        assertThat(painted).contains("class").contains("\u001B[");
    }
}
