// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The reader over the shapes the CLI prints: flat objects of strings, arrays, bools and ints. */
public class JkJsonTest {

    @Test
    public void every_value_kind_round_trips() {
        Map<String, Object> o = JkJson.object(
                JkJson.parse(
                        "{\"s\":\"a\\\"b\\\\c\\n\\u0041\",\"n\":25,\"d\":1.5,\"t\":true,\"f\":false,\"z\":null,"
                                + "\"a\":[\"x\",[1,2],{}],\"o\":{\"k\":\"v\"}}",
                        0),
                Map.of());
        assertEquals("a\"b\\c\nA", o.get("s"));
        assertEquals(25L, o.get("n"));
        assertEquals(1.5, o.get("d"));
        assertEquals(Boolean.TRUE, o.get("t"));
        assertEquals(Boolean.FALSE, o.get("f"));
        assertNull(o.get("z"));
        assertEquals(List.of("x", List.of(1L, 2L), Map.of()), o.get("a"));
        assertEquals(Map.of("k", "v"), o.get("o"));
    }

    @Test
    public void a_bracket_inside_a_string_does_not_end_the_array() {
        Map<String, Object> o = JkJson.object(JkJson.parse("{\"a\":[\"x]y\",\"z\"]}", 0), Map.of());
        assertEquals(List.of("x]y", "z"), o.get("a"));
    }

    @Test
    public void whitespace_and_trailing_text_are_tolerated() {
        Map<String, Object> o =
                JkJson.object(JkJson.parse("  { \"a\" : [ 1 , 2 ] , \"b\" : \"c\" }\nmore", 0), Map.of());
        assertEquals(List.of(1L, 2L), o.get("a"));
        assertEquals("c", o.get("b"));
    }

    @Test
    public void a_truncated_object_is_an_error_not_a_partial_model() {
        assertThrows(IllegalArgumentException.class, () -> JkJson.parse("{\"a\":[\"x\"", 0));
        assertThrows(IllegalArgumentException.class, () -> JkJson.parse("{\"a\" \"x\"}", 0));
    }
}
