// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LayoutTest {

    @Test
    void parse_accepts_known_tokens_case_insensitively() {
        assertThat(Layout.parse("simple")).isEqualTo(Layout.SIMPLE);
        assertThat(Layout.parse("TRADITIONAL")).isEqualTo(Layout.TRADITIONAL);
        assertThat(Layout.parse("Auto")).isEqualTo(Layout.AUTO);
        assertThat(Layout.parse(null)).isEqualTo(Layout.AUTO);
        assertThat(Layout.parse("  ")).isEqualTo(Layout.AUTO);
    }

    /** The scaffold vocabulary is everything parse accepts, default first and auto (detect) last. */
    @Test
    void the_scaffold_vocabulary_names_its_default_first_and_auto_last() {
        assertThat(Layout.SCAFFOLD_TOKENS).containsExactly("traditional", "simple", "auto");
        assertThat(Layout.scaffoldHelp()).isEqualTo("traditional (default) | simple | auto");
        for (String token : Layout.SCAFFOLD_TOKENS)
            assertThat(Layout.parse(token)).isNotNull();
    }

    @Test
    void parse_rejects_unknown_tokens() {
        assertThatThrownBy(() -> Layout.parse("mill"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout must be")
                .hasMessageContaining("mill");
    }

    @Test
    void tomlValue_omits_auto_token_uses_canonical_strings() {
        assertThat(Layout.SIMPLE.tomlValue()).isEqualTo(Layout.TOKEN_SIMPLE);
        assertThat(Layout.TRADITIONAL.tomlValue()).isEqualTo(Layout.TOKEN_TRADITIONAL);
        assertThat(Layout.AUTO.tomlValue()).isNull();
        assertThat(Layout.SIMPLE.token()).isEqualTo(Layout.TOKEN_SIMPLE);
        assertThat(Layout.TRADITIONAL.token()).isEqualTo(Layout.TOKEN_TRADITIONAL);
        assertThat(Layout.AUTO.token()).isEqualTo(Layout.TOKEN_AUTO);
    }
}
