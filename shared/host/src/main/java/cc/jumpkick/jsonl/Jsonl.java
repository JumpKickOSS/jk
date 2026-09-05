// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Dependency-free JSONL field codec for engine and plugin wire lines: readers return defaults on missing/bad
 * fields; {@link #quote} is the writer half and {@link #append} the one splicer. Tree documents use
 * sibling {@link MiniJson}.
 */
public final class Jsonl {

    private Jsonl() {}

    /**
     * Extract a JSON string field value, handling basic escape sequences ({@code \"}, {@code \\},
     * {@code \n}, {@code \r}, {@code \t}). Returns {@code null} when the key is absent.
     */
    public static String str(String json, String key) {
        return strAt(json, indexOfKey(json, key, false));
    }

    /**
     * Like {@link #str} but only the root object's field — not a nested {@code throwable.class}
     * (or any other nested object).
     */
    public static String topStr(String json, String key) {
        return strAt(json, indexOfKey(json, key, true));
    }

    private static String strAt(String json, int keyAt) {
        if (json == null || keyAt < 0) return null;
        int colon = json.indexOf(':', keyAt);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i = appendEscape(json, i + 1, sb);
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Decode the escape whose introducing {@code \\} sits at {@code i - 1}; append the decoded
     * char to {@code sb} and return the index of the last consumed char. One decoder for every
     * string reader — {@link #quote} emits {@code \\uXXXX} for control chars, so a reader without
     * the {@code u} case corrupts any message or stack containing one (ESC, vertical tab, …) at
     * every wire hop. Unknown or malformed escapes are kept literally.
     */
    private static int appendEscape(String s, int i, StringBuilder sb) {
        char n = s.charAt(i);
        switch (n) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
                // Explicit hex check: Integer.parseInt accepts a leading +/- sign, so a
                // malformed backslash-u-123 escape would otherwise decode to garbage and eat 4
                // chars instead of being kept literally as the javadoc promises.
                if (i + 4 < s.length() && isHex4(s, i + 1)) {
                    sb.append((char) Integer.parseInt(s, i + 1, i + 5, 16));
                    return i + 4;
                }
                sb.append('\\').append(n);
            }
            default -> {
                sb.append('\\');
                sb.append(n);
            }
        }
        return i;
    }

    /** True when the four chars at {@code from} are all hex digits. */
    private static boolean isHex4(String s, int from) {
        for (int k = from; k < from + 4; k++) {
            char c = s.charAt(k);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * Index of {@code "key"} that starts a field. When {@code topLevelOnly}, the match must sit in
     * the root object (depth 1), so {@code throwable.class} does not shadow a missing top-level
     * {@code class}.
     */
    static int indexOfKey(String json, String key, boolean topLevelOnly) {
        if (json == null || key == null) return -1;
        String needle = "\"" + key + "\"";
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                boolean atRoot = !topLevelOnly || depth == 1;
                if (atRoot && json.startsWith(needle, i)) {
                    int after = i + needle.length();
                    while (after < json.length() && json.charAt(after) == ' ') after++;
                    if (after < json.length() && json.charAt(after) == ':') return i;
                }
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') depth++;
            else if ((c == '}' || c == ']') && depth > 0) depth--;
        }
        return -1;
    }

    /** Extract a JSON integer field, returning {@code defaultVal} when absent or non-numeric. */
    public static int intValue(String json, String key, int defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        boolean neg = end < json.length() && json.charAt(end) == '-';
        if (neg) end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start || (neg && end == start + 1)) return defaultVal;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /** Extract a JSON long field, returning {@code defaultVal} when absent or non-numeric. */
    public static long longValue(String json, String key, long defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        boolean neg = end < json.length() && json.charAt(end) == '-';
        if (neg) end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start || (neg && end == start + 1)) return defaultVal;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /** Extract a JSON number field (int or decimal), returning {@code defaultVal} when absent. */
    public static double doubleValue(String json, String key, double defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (json.startsWith("null", start)) return defaultVal;
        int end = start;
        if (end < json.length() && (json.charAt(end) == '-' || json.charAt(end) == '+')) end++;
        boolean sawDigit = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (Character.isDigit(c)) {
                sawDigit = true;
                end++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                end++;
            } else {
                break;
            }
        }
        if (!sawDigit) return defaultVal;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /** Extract a JSON boolean field, returning {@code defaultVal} when absent. */
    public static boolean bool(String json, String key, boolean defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return defaultVal;
    }

    /** Returns {@code true} when the key is present with any non-null, non-"null" value. */
    public static boolean has(String json, String key) {
        if (json == null) return false;
        return json.contains("\"" + key + "\":");
    }

    /**
     * Extract a JSON string-array field ({@code "key":["a","b","c"]}). Returns an empty list when the
     * key is absent or the value is not an array. Does not handle nested arrays or non-string
     * elements.
     */
    public static List<String> strArray(String json, String key) {
        if (json == null) return Collections.emptyList();
        // Tolerate whitespace after the colon: jk's own encoders emit compact JSON, but MCP and
        // hand-written requests may be pretty-printed, and a reader that only accepts `"k":[`
        // silently returns empty for `"k": [` — which reads as "the caller passed no tags".
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return Collections.emptyList();
        start += needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '[') return Collections.emptyList();
        start++;
        // The array's closing ']' is the first one that isn't inside a quoted element — a naive
        // indexOf(']') truncates any value that itself contains ']' (e.g. TOML tables like
        // "[project]" carried as a scaffold param or generated-file content).
        int end = arrayEnd(json, start);
        if (end < 0) return Collections.emptyList();
        String content = json.substring(start, end).trim();
        if (content.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < content.length() && content.charAt(i) != '"') {
                    char c = content.charAt(i);
                    if (c == '\\' && i + 1 < content.length()) {
                        i = appendEscape(content, i + 1, sb);
                    } else {
                        sb.append(c);
                    }
                    i++;
                }
                result.add(sb.toString());
                i++; // closing quote
            } else {
                i++;
            }
        }
        return result;
    }

    /**
     * Index of the {@code ']'} that closes the array beginning at {@code start} — the first one that
     * lies outside a quoted element (quotes and their {@code \\}-escapes are skipped), or {@code -1}
     * if unterminated. A plain {@code indexOf(']')} would stop at a {@code ']'} inside an element's
     * own text.
     */
    private static int arrayEnd(String json, int start) {
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++; // skip the escaped char (incl. an escaped quote)
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract the raw JSON for a nested object field ({@code "key":{...}}). Returns the {@code {...}}
     * string (suitable for passing back to other {@code Jsonl} methods), or {@code null} when
     * absent.
     */
    public static String nested(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":{";
        int start = json.indexOf(needle);
        if (start < 0) {
            // Also handle "key": { with a space
            needle = "\"" + key + "\": {";
            start = json.indexOf(needle);
            if (start < 0) return null;
        }
        // Walk forward to find the matching closing brace.
        int braceStart = json.indexOf('{', start + needle.length() - 1);
        if (braceStart < 0) return null;
        int depth = 1;
        int i = braceStart + 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '"') {
                // skip string to avoid counting braces inside strings
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        return json.substring(braceStart, i);
    }

    /**
     * Extract a flat string-to-string map field ({@code "key":{"a":"1","b":"2"}}). Returns an
     * empty (mutable-safe, insertion-ordered) map when absent or malformed. The ONE wire encoding
     * for maps — parallel name/value arrays are gone.
     */
    public static Map<String, String> strMap(String json, String key) {
        var out = new LinkedHashMap<String, String>();
        String obj = nested(json, key);
        if (obj == null) return out;
        // obj is "{...}" — scan "k":"v" pairs at depth 1.
        int i = 1;
        while (i < obj.length()) {
            if (obj.charAt(i) == '"') {
                int[] pos = {i};
                String k = readString(obj, pos);
                i = pos[0];
                while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == ':')) i++;
                if (i < obj.length() && obj.charAt(i) == '"') {
                    pos[0] = i;
                    String v = readString(obj, pos);
                    i = pos[0];
                    out.put(k, v);
                }
            } else {
                i++;
            }
        }
        return out;
    }

    /** Read a quoted string starting at {@code pos[0]} (on the opening quote); advances past it. */
    private static String readString(String s, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int i = pos[0] + 1;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i = appendEscape(s, i + 1, sb);
            } else {
                sb.append(c);
            }
            i++;
        }
        pos[0] = i + 1;
        return sb.toString();
    }

    /**
     * Encode a flat string map as a JSON object ({@code {"a":"1"}}), keys in iteration order —
     * the writer half of {@link #strMap}. Null maps encode as {@code {}}.
     */
    public static String map(Map<String, String> m) {
        if (m == null || m.isEmpty()) return "{}";
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return b.append('}').toString();
    }

    /** Encode a list of strings as a JSON array of quoted strings: {@code ["a","b"]}. */
    public static String array(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(quote(values.get(i)));
        }
        return b.append(']').toString();
    }

    /**
     * Splice a pre-encoded {@code "key":value} fragment into an already-encoded single-line JSON
     * object, before its closing brace — {@code append("{\"a\":1}", "\"b\":2")} yields
     * {@code {"a":1,"b":2}}. The ONE splicer.
     *
     * <p>{@code fields} carries no surrounding braces and no leading comma; {@code null} or blank
     * is a no-op that returns {@code object} byte-identical (so a caller can splice
     * unconditionally). The object is validated either way, because "this string is not an encoded
     * object" is a programming error whether or not there is anything to add to it.
     *
     * <p>jk had six hand-rolled brace chops with three different malformed-input policies (throw,
     * return unchanged, splice at the last brace wherever it sits) and every one of them emitted
     * the invalid {@code {,"b":2}} when handed an empty object — the separator was a constant
     * comma rather than a function of the object.
     *
     * @throws IllegalArgumentException when {@code object} is not a single-line {@code {…}}
     */
    public static String append(String object, String fields) {
        if (object == null
                || object.length() < 2
                || object.charAt(0) != '{'
                || object.charAt(object.length() - 1) != '}') {
            throw new IllegalArgumentException("append needs an encoded single-line JSON object, got: " + object);
        }
        if (fields == null || fields.isBlank()) return object;
        String separator = object.length() == 2 ? "" : ",";
        return object.substring(0, object.length() - 1) + separator + fields + "}";
    }

    /**
     * Escape a string into a JSON string literal, surrounding quotes included ({@code foo"bar} →
     * {@code "foo\"bar"}). Control characters below 0x20 are emitted as {@code \\uXXXX}. The inverse
     * of {@link #str} for the escape sequences both sides handle.
     *
     * <p>This is the writer half: a plugin building a protocol line uses it to encode arbitrary
     * string values (diagnostics, paths, messages). A {@code null} value encodes as the bare JSON
     * literal {@code null} (not a quoted string), so {@code "msg":} + {@code quote(maybeNull)} is
     * always valid JSON.
     */
    public static String quote(@Nullable String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
