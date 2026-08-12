// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RichTextParseTest {

    @Test
    void plain_is_not_markup() {
        assertThat(RichText.plain("[bold]x[/]").plainText()).isEqualTo("[bold]x[/]");
    }

    @Test
    void parse_strips_tags_from_plain_text() {
        assertThat(RichText.parse("v[bold]1.2.3[/] (pid [yellow]42[/])").plainText())
                .isEqualTo("v1.2.3 (pid 42)");
    }

    @Test
    void nested_and_combined_openers() {
        assertThat(RichText.parse("[bold][yellow]hot[/][/]").plainText()).isEqualTo("hot");
        assertThat(RichText.parse("[bold yellow]hot[/]").plainText()).isEqualTo("hot");
    }

    @Test
    void escaped_bracket() {
        assertThat(RichText.parse("[[bold]").plainText()).isEqualTo("[bold]");
    }

    @Test
    void hex_and_link_and_theme_tokens() {
        assertThat(RichText.parse("[#3D9BFF]blue[/]").plainText()).isEqualTo("blue");
        assertThat(RichText.parse("[#3df]short[/]").plainText()).isEqualTo("short");
        assertThat(RichText.parse("[link https://jumpkick.build]site[/]").plainText())
                .isEqualTo("site");
        assertThat(RichText.parse("[success]ok[/] [path]src[/]").plainText()).isEqualTo("ok src");
    }

    @Test
    void plus_and_of_compose() {
        RichText t = RichText.of(RichText.plain("a"), RichText.parse("[bold]b[/]"));
        assertThat(t.plus(RichText.plain("c")).plainText()).isEqualTo("abc");
    }

    @Test
    void unknown_name_throws() {
        assertThatThrownBy(() -> RichText.parse("[not-a-color]x[/]"))
                .isInstanceOf(RichText.ParseException.class)
                .hasMessageContaining("unknown style name");
    }

    @Test
    void unclosed_tag_throws() {
        assertThatThrownBy(() -> RichText.parse("[bold]x"))
                .isInstanceOf(RichText.ParseException.class)
                .hasMessageContaining("unclosed tag");
    }

    @Test
    void unmatched_closer_throws() {
        assertThatThrownBy(() -> RichText.parse("x[/]"))
                .isInstanceOf(RichText.ParseException.class)
                .hasMessageContaining("unmatched closer");
    }

    @Test
    void unclosed_bracket_throws() {
        assertThatThrownBy(() -> RichText.parse("[bold"))
                .isInstanceOf(RichText.ParseException.class)
                .hasMessageContaining("unclosed '['");
    }

    @Test
    void link_requires_url() {
        assertThatThrownBy(() -> RichText.parse("[link]x[/]"))
                .isInstanceOf(RichText.ParseException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void bad_hex_throws() {
        assertThatThrownBy(() -> RichText.parse("[#GGGGGG]x[/]"))
                .isInstanceOf(RichText.ParseException.class)
                .hasMessageContaining("#RGB");
    }
}
