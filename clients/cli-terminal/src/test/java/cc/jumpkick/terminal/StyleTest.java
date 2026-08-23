// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StyleTest {
    @Test
    void attributeLeadingSgrThenFg() {
        String body = Style.EMPTY.bold().foreground(0, 188, 212).sgrBody();
        assertThat(body).isEqualTo("1;38;2;0;188;212");
        assertThat(body).doesNotStartWith("38");
    }

    @Test
    void crossedOutEmitsSgr9() {
        assertThat(Style.EMPTY.dim().crossedOut().sgrBody()).isEqualTo("2;9");
    }

    @Test
    void mergeOrsFlagsAndPrefersOverColor() {
        Style base = Style.EMPTY.bold().foreground(1, 2, 3);
        Style over = Style.EMPTY.italic().foreground(9, 9, 9);
        Style m = base.merge(over);
        assertThat(m.isBold()).isTrue();
        assertThat(m.isItalic()).isTrue();
        assertThat(m.sgrBody()).contains("38;2;9;9;9");
    }

    @Test
    void renderDoesNotRewriteBoxDrawing() {
        String box = "┌─┐";
        assertThat(Style.EMPTY.bold().render(box)).contains(box);
        assertThat(Style.EMPTY.render(box)).isEqualTo(box);
    }

    @Test
    void emptyItalicReplacesDefault() {
        assertThat(Style.EMPTY.italic().sgrBody()).isEqualTo("3");
    }

    @Test
    void gradientStampsBoldOnEveryCell() {
        Styled styled = new StyledBuilder()
                .append("A", Style.EMPTY.bold().foreground(1, 2, 3))
                .append("B", Style.EMPTY.bold().foreground(4, 5, 6))
                .build();
        String ansi = styled.toAnsi();
        int first = ansi.indexOf("1;38;2;1;2;3");
        int second = ansi.indexOf("1;38;2;4;5;6");
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(second).isGreaterThan(first);
        assertThat(styled.plain()).isEqualTo("AB");
        assertThat(styled.columns()).isEqualTo(2);
    }
}
