// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonOutTest {

    @Test
    void primitives_roundtrip() {
        assertEquals("null", JsonOut.string(null));
        assertEquals("\"hi\"", JsonOut.string("hi"));
        assertEquals("42", JsonOut.string(42));
        assertEquals("3.14", JsonOut.string(3.14));
        assertEquals("true", JsonOut.string(true));
    }

    @Test
    void strings_escape_quotes_and_backslashes() {
        assertEquals("\"it\\\\'s \\\"that\\\"\"", JsonOut.string("it\\'s \"that\""));
    }

    @Test
    void strings_escape_control_chars() {
        assertEquals("\"a\\nb\\tc\\u0001d\"", JsonOut.string("a\nb\tc\u0001d"));
    }

    @Test
    void objects_preserve_insertion_order_and_quote_keys() {
        var m = new LinkedHashMap<String, Object>();
        m.put("e", "started");
        m.put("id", "x");
        assertEquals("{\"e\":\"started\",\"id\":\"x\"}", JsonOut.object(m));
    }

    @Test
    void arrays_render_inline() {
        assertEquals("[1,2,3]", JsonOut.string(List.of(1, 2, 3)));
    }

    @Test
    void nested_object_inside_array() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("name", "foo");
        assertEquals("[{\"name\":\"foo\"}]", JsonOut.string(List.of(inner)));
    }

    /**
     * The wire carries {@code EventType.wire()}, never the Java constant name: {@link
     * JsonEventWriter} converts before the map reaches the encoder. Asserting
     * {@code JsonOut.string(EventType.PLAN_STARTED)} instead pinned {@code "PLAN_STARTED"} — a
     * token nothing emits — and would have stayed green through a rename of the wire form.
     */
    @Test
    void the_event_writer_emits_the_wire_name_not_the_java_constant() {
        var buffer = new ByteArrayOutputStream();
        new JsonEventWriter(new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKT:"))
                .write(EventType.PLAN_STARTED, Map.of("id", "x"));

        String line = buffer.toString(StandardCharsets.UTF_8).strip();
        assertTrue(line.contains("\"event\":\"plan_started\""), line);
        assertFalse(line.contains("PLAN_STARTED"), line);
    }

    /**
     * Totality: a payload value the encoder has no case for stringifies rather than throwing.
     *
     * <p>{@link Path} gets its own arm: it is an {@code Iterable<Path>} of single-name
     * {@code Path}s, so the array branch recursed forever and threw {@code StackOverflowError} on
     * the very example the encoder's own comment claimed it handled.
     */
    @Test
    void an_unmodelled_value_stringifies_rather_than_throwing() {
        assertEquals("\"PT5S\"", JsonOut.string(Duration.ofSeconds(5)));
        assertEquals("\"" + Path.of("/tmp/report.xml") + "\"", JsonOut.string(Path.of("/tmp/report.xml")));
    }
}
