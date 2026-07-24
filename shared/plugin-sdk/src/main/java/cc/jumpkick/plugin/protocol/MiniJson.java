// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tree-shaped JSON parse/write ({@code Map}/{@code List}/{@code String}/{@code Number}/
 * {@code Boolean}/{@code null}) for HTTP/MCP/journal. Sibling of {@link Jsonl} (wire field codec +
 * {@link Jsonl#quote}); this is the single tree codec — never reimplement escaping here.
 *
 * <p>Lives in plugin-sdk at the SPI language floor ({@code --release 17}); written without
 * pattern-switch (Java 21+) so worker JVMs on project JDK 17+ can load the same classes (JK-1133).
 */
public final class MiniJson {

    /** Serialize {@code value} (Map/List/String/Number/Boolean/null) as compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1);
        return sb.toString();
    }

    /** As {@link #write(Object)}, pretty-printed with 2-space indentation. */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    /** {@code indent < 0} = compact; otherwise the current pretty-print depth. */
    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String) {
            sb.append(Jsonl.quote((String) value));
            return;
        }
        if (value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof Double) {
            Double d = (Double) value;
            // Integral doubles (the parser's number type) print without the ".0" so
            // parse→write round-trips don't reformat whole numbers.
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.007199254740992E15) {
                sb.append((long) (double) d);
            } else {
                sb.append(d);
            }
            return;
        }
        if (value instanceof Float) {
            writeValue(sb, ((Float) value).doubleValue(), indent);
            return;
        }
        if (value instanceof Number) {
            sb.append(value);
            return;
        }
        if (value instanceof Map) {
            writeObject(sb, (Map<?, ?>) value, indent);
            return;
        }
        if (value instanceof List) {
            writeArray(sb, (List<?>) value, indent);
            return;
        }
        throw new IllegalArgumentException("not JSON-representable: " + value.getClass().getName());
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newlineIndent(sb, indent < 0 ? -1 : indent + 1);
            sb.append(Jsonl.quote(String.valueOf(e.getKey()))).append(':');
            if (indent >= 0) sb.append(' ');
            writeValue(sb, e.getValue(), indent < 0 ? -1 : indent + 1);
        }
        newlineIndent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            first = false;
            newlineIndent(sb, indent < 0 ? -1 : indent + 1);
            writeValue(sb, item, indent < 0 ? -1 : indent + 1);
        }
        newlineIndent(sb, indent);
        sb.append(']');
    }

    private static void newlineIndent(StringBuilder sb, int indent) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** Parse a complete JSON document; trailing non-whitespace is an error. */
    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (p.pos != json.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
            case 'f':
                return parseBoolean();
            case 'n':
                return parseNull();
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            map.put(key, parseValue());
            skipWhitespace();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at offset " + (pos - 1));
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at offset " + (pos - 1));
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new IllegalArgumentException("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + esc + " at offset " + (pos - 1));
            }
        }
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("bad literal at offset " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("bad literal at offset " + pos);
    }

    private Double parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
        if (pos == start) throw new IllegalArgumentException("unexpected character at offset " + pos);
        return Double.parseDouble(src.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected '" + c + "' at offset " + (pos - 1));
    }
}
