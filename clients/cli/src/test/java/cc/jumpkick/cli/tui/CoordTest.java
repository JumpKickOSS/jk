// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoordTest {

    @Test
    void gav_plain_text_has_three_segments() {
        assertThat(Coord.gav("com.acme", "lib", "1.2.3").text().plainText()).isEqualTo("com.acme:lib:1.2.3");
        assertThat(Coord.module("com.acme:lib").text().plainText()).isEqualTo("com.acme:lib");
        assertThat(Coord.module("junit").text().plainText()).isEqualTo("junit");
    }

    @Test
    void toString_renders() {
        assertThat(Coord.ga("a", "b").toString()).contains("a").contains("b");
    }
}
