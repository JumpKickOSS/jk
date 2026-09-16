// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A JSON reader small enough to keep the plugin dependency-free: objects become {@link Map},
 * arrays {@link List}, numbers {@link Long} or {@link Double}, {@code null} stays {@code null}.
 * Reads one value and ignores whatever follows it.
 */
final class JkJson {

    private final String text;
    private int pos;

    private JkJson(String text) {
        this.text = text;
    }

    /** Parse the value starting at {@code offset}; the object the CLI prints starts at a brace. */
    static @Nullable Object parse(String text, int offset) {
        JkJson p = new JkJson(text);
        p.pos = offset;
        return p.value();
    }

    /** The parsed value, or {@code fallback} when it is not an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(@Nullable Object value, Map<String, Object> fallback) {
        return value instanceof Map ? (Map<String, Object>) value : fallback;
    }

    private @Nullable Object value() {
        skipWs();
        if (pos >= text.length()) throw error("unexpected end of input");
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            if (peek() != '"') throw error("expected a key");
            String key = string();
            skipWs();
            if (peek() != ':') throw error("expected ':'");
            pos++;
            Object v = value();
            out.put(key, v);
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') return out;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') return out;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) break;
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("truncated \\u escape");
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(e); // \" \\ \/
            }
        }
        throw error("unterminated string");
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String raw = text.substring(start, pos);
        if (raw.isEmpty()) throw error("unexpected character '" + text.charAt(pos) + "'");
        try {
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) return Long.parseLong(raw);
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw error("bad number " + raw);
        }
    }

    private @Nullable Object literal(String word, @Nullable Object value) {
        if (!text.startsWith(word, pos)) throw error("expected " + word);
        pos += word.length();
        return value;
    }

    private char peek() {
        if (pos >= text.length()) throw error("unexpected end of input");
        return text.charAt(pos);
    }

    private void skipWs() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("ide-model JSON: " + what + " at offset " + pos);
    }
}
