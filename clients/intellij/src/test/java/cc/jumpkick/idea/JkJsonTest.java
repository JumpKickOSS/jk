// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The reader over the shapes the CLI prints: flat objects of strings, arrays, bools and ints. */
class JkJsonTest {

    @Test
    void every_value_kind_round_trips() {
        Map<String, Object> o = JkJson.object(
                JkJson.parse(
                        "{\"s\":\"a\\\"b\\\\c\\n\\u0041\",\"n\":25,\"d\":1.5,\"t\":true,\"f\":false,\"z\":null,"
                                + "\"a\":[\"x\",[1,2],{}],\"o\":{\"k\":\"v\"}}",
                        0),
                Map.of());
        assertThat(o.get("s")).isEqualTo("a\"b\\c\nA");
        assertThat(o.get("n")).isEqualTo(25L);
        assertThat(o.get("d")).isEqualTo(1.5);
        assertThat(o.get("t")).isEqualTo(Boolean.TRUE);
        assertThat(o.get("f")).isEqualTo(Boolean.FALSE);
        assertThat(o.get("z")).isNull();
        assertThat(o.get("a")).isEqualTo(List.of("x", List.of(1L, 2L), Map.of()));
        assertThat(o.get("o")).isEqualTo(Map.of("k", "v"));
    }

    @Test
    void a_bracket_inside_a_string_does_not_end_the_array() {
        Map<String, Object> o = JkJson.object(JkJson.parse("{\"a\":[\"x]y\",\"z\"]}", 0), Map.of());
        assertThat(o.get("a")).isEqualTo(List.of("x]y", "z"));
    }

    @Test
    void whitespace_and_trailing_text_are_tolerated() {
        Map<String, Object> o =
                JkJson.object(JkJson.parse("  { \"a\" : [ 1 , 2 ] , \"b\" : \"c\" }\nmore", 0), Map.of());
        assertThat(o.get("a")).isEqualTo(List.of(1L, 2L));
        assertThat(o.get("b")).isEqualTo("c");
    }

    @Test
    void a_truncated_object_is_an_error_not_a_partial_model() {
        assertThatThrownBy(() -> JkJson.parse("{\"a\":[\"x\"", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JkJson.parse("{\"a\" \"x\"}", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
