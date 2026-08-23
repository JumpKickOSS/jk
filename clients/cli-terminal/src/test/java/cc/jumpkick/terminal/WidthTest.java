// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WidthTest {
    @Test
    void goldenGlyphs() {
        assertThat(Width.wcwidth('─')).isEqualTo(1);
        assertThat(Width.wcwidth('│')).isEqualTo(1);
        assertThat(Width.wcwidth('┌')).isEqualTo(1);
        assertThat(Width.wcwidth('\uE0B0')).isEqualTo(1);
        assertThat(Width.wcwidth('\uE0B4')).isEqualTo(1);
        assertThat(Width.wcwidth('\uE0B6')).isEqualTo(1);
        assertThat(Width.wcwidth('\u2800')).isEqualTo(1);
        assertThat(Width.wcwidth('中')).isEqualTo(2);
        assertThat(Width.wcwidth(0x1F600)).isEqualTo(2);
        assertThat(Width.wcwidth(0xFE0F)).isEqualTo(0);
        assertThat(Width.wcwidth(0x200D)).isEqualTo(0);
        assertThat(Width.columns("😀\uFE0F")).isEqualTo(2);
    }

    @Test
    void stripOscHyperlink() {
        String linked = Ansi.hyperlink("https://example.com/path", "hi");
        assertThat(Width.stripAnsi(linked)).isEqualTo("hi");
        assertThat(Width.columns(linked)).isEqualTo(2);
        assertThat(Width.stripAnsi(linked)).doesNotContain("example");
    }

    @Test
    void unterminatedOscConsumesToEnd() {
        String truncated = "before \u001b]8;;https://example.com/clipped";
        assertThat(Width.stripAnsi(truncated)).isEqualTo("before ");
        assertThat(Width.columns(truncated)).isEqualTo("before ".length());
    }

    @Test
    void skipEscapeCsiAndOsc() {
        String csi = "\u001b[31mX";
        assertThat(Width.skipEscape(csi, 0)).isEqualTo(5);
        String oscBel = "\u001b]8;;\u0007X";
        assertThat(Width.skipEscape(oscBel, 0)).isEqualTo(oscBel.indexOf('X'));
        String oscSt = "\u001b]8;;\u001b\\X";
        assertThat(Width.skipEscape(oscSt, 0)).isEqualTo(7);
        String unterminated = "\u001b]8;;clip";
        assertThat(Width.skipEscape(unterminated, 0)).isEqualTo(unterminated.length());
        assertThat(Width.skipEscape("\u001b(BX", 0)).isEqualTo(2);
    }
}
