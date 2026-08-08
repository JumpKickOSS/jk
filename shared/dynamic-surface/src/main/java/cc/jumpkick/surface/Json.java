// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for the JSON shapes GraalVM publishes in {@code META-INF/native-image}. Objects become
 * {@link LinkedHashMap}, arrays {@link List}, numbers {@link Double}, and the rest map to their
 * Java equivalents.
 *
 * <p>Hand-written because this module is dependency-free so the shrink worker and the
 * native-image driver can both link it, and because the inputs are small documents in a known
 * shape. Reading only — {@link ReachabilityMetadataEmitter} writes.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** Parse a whole document. Throws {@link IllegalArgumentException} on malformed input. */
    static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.value();
        json.skipWhitespace();
        if (json.pos < json.src.length()) {
            throw new IllegalArgumentException("trailing content at offset " + json.pos);
        }
        return value;
    }

    /** {@code map.get(key)} when {@code o} is a map and the value is a string; else null. */
    static String str(Object o, String key) {
        Object value = o instanceof Map<?, ?> map ? map.get(key) : null;
        return value instanceof String s ? s : null;
    }

    /** {@code map.get(key)} as a list; empty when absent or not a list. */
    static List<?> list(Object o, String key) {
        Object value = o instanceof Map<?, ?> map ? map.get(key) : null;
        return value instanceof List<?> l ? l : List.of();
    }

    /** {@code map.get(key)} as a map; null when absent or not a map. */
    static Map<?, ?> map(Object o, String key) {
        Object value = o instanceof Map<?, ?> map ? map.get(key) : null;
        return value instanceof Map<?, ?> m ? m : null;
    }

    private Object value() {
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            out.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') return out;
            if (c != ',') throw new IllegalArgumentException("expected , or } at offset " + (pos - 1));
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') return out;
            if (c != ',') throw new IllegalArgumentException("expected , or ] at offset " + (pos - 1));
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"', '\\', '/' -> sb.append(esc);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + esc + " at offset " + (pos - 1));
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw new IllegalArgumentException("unexpected character at offset " + pos);
        return Double.valueOf(src.substring(start, pos));
    }

    private Object literal(String text, Object result) {
        if (!src.startsWith(text, pos)) throw new IllegalArgumentException("bad literal at offset " + pos);
        pos += text.length();
        return result;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        return src.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected " + c + " at offset " + (pos - 1));
    }
}
