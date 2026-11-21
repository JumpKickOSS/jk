// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

    @Test
    void parses_the_reachability_index_shape() {
        Object parsed = MiniJson.parse("""
                [
                  {
                    "latest" : true,
                    "metadata-version" : "42.7.3",
                    "tested-versions" : ["42.7.3", "42.7.4"],
                    "description" : "quote \\" and \\u00e9 and \\n"
                  },
                  { "metadata-version" : "42.3.4", "tested-versions" : [] }
                ]
                """);
        assertThat(parsed).isInstanceOf(List.class);
        List<?> entries = (List<?>) requireNonNull(parsed);
        assertThat(entries).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) entries.get(0);
        assertThat(first.get("latest")).isEqualTo(Boolean.TRUE);
        assertThat(first.get("metadata-version")).isEqualTo("42.7.3");
        assertThat(first.get("tested-versions")).isEqualTo(List.of("42.7.3", "42.7.4"));
        assertThat(first.get("description")).isEqualTo("quote \" and é and \n");
    }

    @Test
    void parses_scalars_nesting_and_numbers() {
        assertThat(MiniJson.parse("{\"a\":{\"b\":[1,2.5,-3e2,null,false]}}"))
                .isEqualTo(Map.of("a", Map.of("b", Arrays.asList(1L, 2.5, -300.0, null, false))));
        assertThat(MiniJson.parse("\"plain\"")).isEqualTo("plain");
        assertThat(MiniJson.parse(" true ")).isEqualTo(true);
    }

    @Test
    void integers_parse_as_longs_with_every_digit() {
        long past2To53 = 9_007_199_254_740_993L;
        assertThat(MiniJson.parse("{\"n\":" + past2To53 + "}")).isEqualTo(Map.of("n", past2To53));
        assertThat(MiniJson.parse(Long.toString(Long.MIN_VALUE))).isEqualTo(Long.MIN_VALUE);
        assertThat(MiniJson.write(MiniJson.parse("[" + Long.MAX_VALUE + "]"))).isEqualTo("[" + Long.MAX_VALUE + "]");
        assertThat(MiniJson.parse("1.0")).isEqualTo(1.0);
        assertThat(MiniJson.parse("1e3")).isEqualTo(1000.0);
        assertThat(MiniJson.parse("12345678901234567890")).isEqualTo(1.2345678901234567e19);
        assertThatThrownBy(() -> MiniJson.parse("-")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parse("1-2")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numbers_follow_the_json_grammar_where_java_is_more_lenient() {
        // Long.parseLong and Double.parseDouble take each of these; RFC 8259 takes none.
        for (String bad : List.of("+5", "01", "-01", "00", "1.", ".5", "-.5", "1.e3", "1e", "1e+", "--1", "1.5.2")) {
            assertThatThrownBy(() -> MiniJson.parse(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("bad number at offset 0");
            assertThatThrownBy(() -> MiniJson.parseRelaxed(bad)).as(bad).hasMessage("bad number at offset 0");
        }
        assertThatThrownBy(() -> MiniJson.parse("{\"a\":[1, 01]}")).hasMessage("bad number at offset 9");
        assertThat(MiniJson.parse("[0, -0, 10, 0.5, -0.5, 1e5, 1E-2, 2.5e+3, -0e0]"))
                .isEqualTo(List.of(0L, 0L, 10L, 0.5, -0.5, 1e5, 0.01, 2500.0, -0.0));
    }

    @Test
    void non_finite_numbers_write_as_null() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("nan", Double.NaN);
        obj.put("up", Double.POSITIVE_INFINITY);
        obj.put("down", Float.NEGATIVE_INFINITY);
        String json = MiniJson.write(obj);
        assertThat(json).isEqualTo("{\"nan\":null,\"up\":null,\"down\":null}");
        assertThat(MiniJson.parse(json)).isEqualTo(MiniJson.parse("{\"nan\":null,\"up\":null,\"down\":null}"));
    }

    @Test
    void write_round_trips_through_parse() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("name", "quote \" slash \\ nl \n");
        obj.put("count", 42L);
        obj.put("ratio", 2.5);
        obj.put("on", true);
        obj.put("none", null);
        obj.put("items", List.of("a", "b"));
        String compact = MiniJson.write(obj);
        assertThat(compact).doesNotContain("\n");
        assertThat(MiniJson.parse(compact)).isEqualTo(MiniJson.parse(MiniJson.writePretty(obj)));
        // integral numbers survive a parse→write cycle without growing a ".0"
        assertThat(MiniJson.write(MiniJson.parse("{\"n\":42}"))).isEqualTo("{\"n\":42}");
    }

    @Test
    void rejects_trailing_garbage_and_truncation() {
        assertThatThrownBy(() -> MiniJson.parse("{} extra")).hasMessageContaining("trailing");
        assertThatThrownBy(() -> MiniJson.parse("[1,")).hasMessageContaining("unexpected end");
        assertThatThrownBy(() -> MiniJson.parse("{\"a\" 1}")).hasMessageContaining("expected");
    }

    @Test
    void parse_relaxed_accepts_comments_wherever_whitespace_is_legal() {
        assertThat(MiniJson.parseRelaxed("// leading note\n{\"a\":1}")).isEqualTo(Map.of("a", 1L));
        assertThat(MiniJson.parseRelaxed("{\"a\":1} // trailing note")).isEqualTo(Map.of("a", 1L));
        assertThat(MiniJson.parseRelaxed("{\"a\":1}\n// trailing note with a newline\n"))
                .isEqualTo(Map.of("a", 1L));
        assertThat(MiniJson.parseRelaxed("/* leading */{\"a\":1}/* trailing */"))
                .isEqualTo(Map.of("a", 1L));
        // between every token of an object: before the key, around the colon, before the closer
        assertThat(MiniJson.parseRelaxed("{ /* k */ \"a\" /* c */ : /* v */ 1 /* end */ }"))
                .isEqualTo(Map.of("a", 1L));
        // and of an array, including a line comment that ends at the newline
        assertThat(MiniJson.parseRelaxed("[ // one\n 1, /* two */ 2 ]")).isEqualTo(List.of(1L, 2L));
        // a comment is the only thing separating two tokens
        assertThat(MiniJson.parseRelaxed("[1/* x */,/* y */2]")).isEqualTo(List.of(1L, 2L));
        assertThatThrownBy(() -> MiniJson.parseRelaxed("{/* never closed \n \"a\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unterminated comment at offset 1");
        // a slash that starts neither comment form is still an error
        assertThatThrownBy(() -> MiniJson.parseRelaxed("[1,/2]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_relaxed_tolerates_trailing_commas() {
        assertThat(MiniJson.parseRelaxed("{\"a\":1,}")).isEqualTo(Map.of("a", 1L));
        assertThat(MiniJson.parseRelaxed("[1,2,]")).isEqualTo(List.of(1L, 2L));
        assertThat(MiniJson.parseRelaxed("{\"a\":[1,],}")).isEqualTo(Map.of("a", List.of(1L)));
        assertThat(MiniJson.parseRelaxed("[1, /* done */ ]")).isEqualTo(List.of(1L));
        assertThat(MiniJson.parseRelaxed("{\"a\":1, // done\n}")).isEqualTo(Map.of("a", 1L));
        // one comma is the limit — a second has no value in front of it
        assertThatThrownBy(() -> MiniJson.parseRelaxed("[1,,]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parseRelaxed("{\"a\":1,,}")).isInstanceOf(IllegalArgumentException.class);
        // a leading comma is not a trailing comma
        assertThatThrownBy(() -> MiniJson.parseRelaxed("[,1]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parseRelaxed("{,}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_relaxed_skips_a_byte_order_mark() {
        // Character.isWhitespace('\uFEFF') is false, so the strict skipWhitespace stalls on it
        assertThat(MiniJson.parseRelaxed("\uFEFF{\"a\":1}")).isEqualTo(Map.of("a", 1L));
        assertThat(MiniJson.parseRelaxed("\uFEFF// note\n[1]")).isEqualTo(List.of(1L));
    }

    @Test
    void parse_relaxed_reads_a_vscode_style_settings_file() {
        Object parsed = MiniJson.parseRelaxed("""
                \uFEFF// Place your settings in this file to overwrite the default settings
                {
                  "editor.fontFamily": "JetBrainsMono Nerd Font", // patched font required
                  "editor.fontLigatures": true,
                  /* Terminal overrides live in their own block so the editor font
                     above stays untouched. */
                  "terminal.integrated": {
                    "fontSize": 13,
                    "env.osx": { "JK_NERD_FONT": "1" }, // trailing comma, nested object
                  },
                  "files.exclude": ["**/.git", "**/.jk"],
                }
                """);
        Map<?, ?> settings = (Map<?, ?>) requireNonNull(parsed);
        List<Object> keys = List.copyOf(settings.keySet());
        assertThat(keys)
                .containsExactly("editor.fontFamily", "editor.fontLigatures", "terminal.integrated", "files.exclude");
        assertThat(settings.get("editor.fontFamily")).isEqualTo("JetBrainsMono Nerd Font");
        assertThat(settings.get("editor.fontLigatures")).isEqualTo(Boolean.TRUE);
        Map<?, ?> terminal = (Map<?, ?>) requireNonNull(settings.get("terminal.integrated"));
        assertThat(terminal.get("fontSize")).isEqualTo(13L);
        assertThat(terminal.get("env.osx")).isEqualTo(Map.of("JK_NERD_FONT", "1"));
        assertThat(settings.get("files.exclude")).isEqualTo(List.of("**/.git", "**/.jk"));
    }

    @Test
    void parse_relaxed_keeps_comment_like_text_inside_strings() {
        assertThat(MiniJson.parseRelaxed("{\"a\":\"// not a comment\"}")).isEqualTo(Map.of("a", "// not a comment"));
        assertThat(MiniJson.parseRelaxed("{\"/* key */\":\"v /* still text */ w\"}"))
                .isEqualTo(Map.of("/* key */", "v /* still text */ w"));
        assertThat(MiniJson.parseRelaxed("\"https://example.com/x\"")).isEqualTo("https://example.com/x");
        // an unterminated block comment inside a string is just characters
        assertThat(MiniJson.parseRelaxed("[\"/* dangling\"]")).isEqualTo(List.of("/* dangling"));
        // a BOM inside a string is content, not whitespace
        assertThat(MiniJson.parseRelaxed("\"a\uFEFFb\"")).isEqualTo("a\uFEFFb");
    }

    @Test
    void parse_stays_strict_about_comments_trailing_commas_and_bom() {
        // Regression guard: parse() is the plugin wire path and must reject every JSONC extension.
        assertThatThrownBy(() -> MiniJson.parse("// note\n{\"a\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset 0");
        assertThatThrownBy(() -> MiniJson.parse("{\"a\":1} // note"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing content");
        assertThatThrownBy(() -> MiniJson.parse("/* note */ {\"a\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parse("{\"a\" /* note */ :1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parse("{\"a\":1,}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parse("[1,2,]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parse("\uFEFF{\"a\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset 0");
    }

    @Test
    void parse_takes_only_the_four_json_whitespace_characters() {
        // Character.isWhitespace also takes each of these; RFC 8259 takes none.
        for (char ws : "\u000B\u000C\u001C\u001F  ".toCharArray()) {
            String hex = String.format("U+%04X", (int) ws);
            assertThatThrownBy(() -> MiniJson.parse(ws + "1")).as(hex).hasMessage("unexpected character at offset 0");
            assertThatThrownBy(() -> MiniJson.parse("[1," + ws + "2]"))
                    .as(hex)
                    .hasMessage("unexpected character at offset 3");
            assertThatThrownBy(() -> MiniJson.parse("{\"a\":1" + ws + "}"))
                    .as(hex)
                    .hasMessage("expected ',' or '}' at offset 6");
            assertThatThrownBy(() -> MiniJson.parse("1" + ws)).as(hex).hasMessage("trailing content at offset 1");
            assertThat(MiniJson.parseRelaxed("[1," + ws + "2]")).as(hex).isEqualTo(List.of(1L, 2L));
        }
        assertThat(MiniJson.parse(" \t\r\n{ \"a\" :\t[1 ,\r\n2] }\n")).isEqualTo(Map.of("a", List.of(1L, 2L)));
    }

    @Test
    void parse_refuses_raw_control_characters_in_strings() {
        for (char c : "\u0000\t\n\r\u001F".toCharArray()) {
            String hex = String.format("U+%04X", (int) c);
            assertThatThrownBy(() -> MiniJson.parse("\"a" + c + "b\""))
                    .as(hex)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unescaped control character at offset 2");
            assertThatThrownBy(() -> MiniJson.parse("{\"k" + c + "\":1}"))
                    .as(hex)
                    .hasMessage("unescaped control character at offset 3");
            assertThat(MiniJson.parseRelaxed("\"a" + c + "b\"")).as(hex).isEqualTo("a" + c + "b");
        }
        // Escaped, they are fine, and DEL (U+007F) is not a control character to JSON.
        assertThat(MiniJson.parse("\"\\u0000\\t\\n\\r\\u001f\u007F\"")).isEqualTo("\u0000\t\n\r\u001F\u007F");
        assertThat(MiniJson.parse(MiniJson.write("tab\there\u0001"))).isEqualTo("tab\there\u0001");
    }

    @Test
    void rejects_malformed_unicode_escapes() {
        // Integer.parseInt accepts a leading sign; a leading '+' is not a JSON unicode escape.
        assertThatThrownBy(() -> MiniJson.parse("\"\\u+123\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset 1");
        assertThatThrownBy(() -> MiniJson.parse("\"\\uZZZZ\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parse("\"\\u 041\"")).isInstanceOf(IllegalArgumentException.class);
        // truncated: an IllegalArgumentException, never a StringIndexOutOfBoundsException
        assertThatThrownBy(() -> MiniJson.parse("\"\\u12"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> MiniJson.parse("\"\\u12\""))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(IndexOutOfBoundsException.class);
        // relaxed shares the string scanner, so it shares the check
        assertThatThrownBy(() -> MiniJson.parseRelaxed("{\"a\":\"\\u+123\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        // well-formed escapes still decode, mixed case included
        assertThat(MiniJson.parse("\"\\u0041\\u00e9\\u00C9\"")).isEqualTo("AéÉ");
    }

    @Test
    void rejects_a_string_ending_in_a_lone_backslash() {
        assertThatThrownBy(() -> MiniJson.parse("\"abc\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("offset 4");
        assertThatThrownBy(() -> MiniJson.parseRelaxed("{\"a\":\"v\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(IndexOutOfBoundsException.class);
        // an escaped backslash before the end is a different story: the string is merely unterminated
        assertThatThrownBy(() -> MiniJson.parse("\"abc\\\\")).hasMessageContaining("unterminated string");
    }

    @Test
    void rejects_nesting_past_the_depth_cap() {
        String deepArray = "[".repeat(600) + "]".repeat(600);
        // StackOverflowError is an Error, so it would escape callers that catch RuntimeException
        assertThatThrownBy(() -> MiniJson.parse(deepArray))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(StackOverflowError.class)
                .hasMessageContaining("nesting deeper than 512");
        assertThatThrownBy(() -> MiniJson.parseRelaxed(deepArray))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(StackOverflowError.class);
        assertThatThrownBy(() -> MiniJson.parse("{\"a\":".repeat(600) + "1" + "}".repeat(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(StackOverflowError.class);
        // comfortably under the cap still parses
        assertThat(MiniJson.parse("[".repeat(500) + "]".repeat(500))).isInstanceOf(List.class);
        // and sibling containers don't accumulate: depth pops on the way out
        assertThat(MiniJson.parse("[" + "[],".repeat(999) + "[]]")).isInstanceOf(List.class);
    }
}
