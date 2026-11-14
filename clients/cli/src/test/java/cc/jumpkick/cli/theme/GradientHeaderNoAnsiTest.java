// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import org.junit.jupiter.api.Test;

/** {@code jk why}'s header under {@code --no-ansi} must carry no escape sequence at all. */
class GradientHeaderNoAnsiTest {

    @Test
    void the_header_is_plain_text_under_no_ansi() throws Exception {
        String header = NoAnsi.forced(() -> new JkDarkTheme().gradientHeaderAnsi("Jk - Dependency Lookup"));
        assertThat(header).as("no-ansi header bytes").doesNotContain("\u001b").isEqualTo("Jk - Dependency Lookup");
    }
}
