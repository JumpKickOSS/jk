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
 * pattern-switch (Java 21+) so worker JVMs on project JDK 17+ can load the same classes.
 *
 * <p>{@link #parse} is strict RFC 8259 — it is the wire-protocol path and must stay that way.
 * {@link #parseRelaxed} is the JSONC dialect for human-edited files (comments, trailing commas,
 * byte-order mark); both share one parser, gated on a {@code relaxed} flag.
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
        throw new IllegalArgumentException(
                "not JSON-representable: " + value.getClass().getName());
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

    /**
     * Container nesting cap. Without one, a hostile or merely generated document overflows the
     * stack, and {@code StackOverflowError} is an {@link Error} — it slips straight through every
     * caller that guards a parse with {@code catch (RuntimeException)}. 512 is far past anything
     * hand-written or emitted by a real tool.
     */
    private static final int MAX_DEPTH = 512;

    /** Byte-order mark; {@link Character#isWhitespace} says false, so it needs its own case. */
    private static final char BOM = '\uFEFF';

    private final String src;
    private final boolean relaxed;
    private int pos;
    private int depth;

    private MiniJson(String src, boolean relaxed) {
        this.src = src;
        this.relaxed = relaxed;
    }

    /**
     * Parse a complete JSON document; trailing non-whitespace is an error. Strict: no comments, no
     * trailing commas, no byte-order mark. This is the plugin wire-protocol path — anything lenient
     * belongs in {@link #parseRelaxed} instead.
     */
    public static Object parse(String json) {
        return parse(json, false);
    }

    /**
     * Parse a JSONC document — {@link #parse}'s grammar plus the three concessions human-edited
     * config files need: {@code //} line and block comments wherever whitespace is legal, a
     * trailing comma in objects and arrays, and a leading byte-order mark. None of these are
     * recognized inside a string literal, so {@code {"a":"// text"}} keeps its value verbatim.
     */
    public static Object parseRelaxed(String json) {
        return parse(json, true);
    }

    private static Object parse(String json, boolean relaxed) {
        MiniJson p = new MiniJson(json, relaxed);
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
            case '[':
                // Depth is accounted for here rather than inside parseObject/parseArray: this is
                // the only call site of either, so one push/pop pair covers both containers and no
                // early return path can leak a level.
                if (++depth > MAX_DEPTH) {
                    throw new IllegalArgumentException("nesting deeper than " + MAX_DEPTH + " at offset " + pos);
                }
                Object nested = c == '{' ? parseObject() : parseArray();
                depth--;
                return nested;
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
            // Only reachable after a ',' (the empty object returned above), so a '}' here is a
            // trailing comma.
            if (relaxed && peek() == '}') {
                pos++;
                return map;
            }
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
            skipWhitespace();
            // Same as the object loop: only reachable after a ',', so ']' is a trailing comma.
            if (relaxed && peek() == ']') {
                pos++;
                return list;
            }
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
            // A string ending in a lone backslash (truncated line, half-written file) read one
            // char past the end and raised StringIndexOutOfBoundsException; callers expect every
            // parse failure to be an IllegalArgumentException carrying an offset.
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated escape at offset " + (pos - 1));
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
                    // Explicit hex + bounds check (in Jsonl.appendEscape, same trap):
                    // Integer.parseInt accepts a leading sign, so a four-char run like "+123"
                    // would silently decode to U+0123, and a truncated escape at end of input
                    // threw StringIndexOutOfBoundsException. Jsonl keeps a malformed escape
                    // literally; this parser is strict by contract, so it rejects instead.
                    if (pos + 4 > src.length() || !isHex4(src, pos)) {
                        throw new IllegalArgumentException("bad unicode escape at offset " + (pos - 2));
                    }
                    sb.append((char) Integer.parseInt(src, pos, pos + 4, 16));
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

    /**
     * True when the four chars at {@code from} are all hex digits. Mirrors {@code Jsonl.isHex4},
     * which is private to its own class — the shared piece worth centralizing is escaping
     * ({@link Jsonl#quote}), not a four-char predicate.
     */
    private static boolean isHex4(String s, int from) {
        for (int k = from; k < from + 4; k++) {
            char c = s.charAt(k);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * Skip whitespace, plus — in relaxed mode — comments and byte-order marks. Every token boundary
     * routes through here, which is what makes a comment legal anywhere whitespace is legal;
     * {@link #parseString} never does, which is what keeps a value of {@code "// text"} intact.
     */
    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || (relaxed && c == BOM)) {
                pos++;
                continue;
            }
            if (relaxed && c == '/' && skipComment()) continue;
            return;
        }
    }

    /**
     * At a {@code '/'}: consume a line comment (to end of line) or a block comment (slash-star to
     * star-slash) and report true; report false when the slash introduces neither, leaving the
     * caller to fail on it as an unexpected character.
     */
    private boolean skipComment() {
        if (pos + 1 >= src.length()) return false;
        char kind = src.charAt(pos + 1);
        if (kind == '/') {
            pos += 2;
            while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') pos++;
            return true;
        }
        if (kind == '*') {
            int end = src.indexOf("*/", pos + 2);
            if (end < 0) throw new IllegalArgumentException("unterminated comment at offset " + pos);
            pos = end + 2;
            return true;
        }
        return false;
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
