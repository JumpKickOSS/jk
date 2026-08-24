// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Jsonl#quote} is the tree's one JSON string escaper. A character below 0x20 is not legal
 * raw inside a JSON string, and application output carries them routinely — ANSI escapes (U+001B)
 * most of all — so a writer that passes them through emits a document its peer cannot parse.
 */
class JsonlControlCharTest {

    @Test
    void control_characters_are_escaped_not_passed_through() {
        assertThat(Jsonl.quote("a\u001bb")).isEqualTo("\"a\\u001bb\"");
        assertThat(Jsonl.quote("a\u000bb")).isEqualTo("\"a\\u000bb\"");
        assertThat(Jsonl.quote("a\u0007b")).isEqualTo("\"a\\u0007b\"");
        assertThat(Jsonl.quote("\u0000")).isEqualTo("\"\\u0000\"");
    }

    @Test
    void the_named_escapes_stay_named() {
        assertThat(Jsonl.quote("a\nb\tc\rd\"e\\f")).isEqualTo("\"a\\nb\\tc\\rd\\\"e\\\\f\"");
    }

    @Test
    void a_quoted_control_character_round_trips_through_the_parser() {
        String ansi = "\u001b[38;2;0;179;104mok\u001b[0m";
        String doc = "{\"msg\":" + Jsonl.quote(ansi) + "}";
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(doc);
        assertThat(parsed.get("msg")).isEqualTo(ansi);
    }

    @Test
    void str_array_stops_at_the_bracket_that_closes_the_array() {
        assertThat(Jsonl.strArray("{\"t\":[\"[slow]\"]}", "t")).containsExactly("[slow]");
        assertThat(Jsonl.strArray("{\"t\":[\"a]b\",\"c\"]}", "t")).containsExactly("a]b", "c");
    }

    @Test
    void str_array_tolerates_whitespace_after_the_colon() {
        assertThat(Jsonl.strArray("{\"t\": [\"a\"]}", "t")).containsExactly("a");
        assertThat(Jsonl.strArray("{\"t\":\n  [\"a\"]}", "t")).containsExactly("a");
    }
}
