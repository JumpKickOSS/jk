// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Dependency-free string walk over a JUnit Platform {@code UniqueId}
 * ({@code [engine:…]/[class:…]/[method:…]}) — the fallback grammar both sides of the test fork
 * share. The runner ({@code JUnitUniqueId}) prefers the platform-typed
 * {@code UniqueId.parse} and falls back here for a malformed id; the engine
 * ({@code JUnitLauncher}) has no junit-platform on its classpath and uses this walk whenever a
 * JSONL event lacks the split {@code testClass}/{@code testEngine} fields. One owner, so a
 * Platform quirk (the {@code [nested-class:…]} → {@code $} join, the {@code %XX} escape set)
 * cannot diverge across the fork.
 *
 * <p>Vendored into the runner jar, which rides the user's test JVM — this class must stay
 * release-17 clean like the rest of {@code :plugin-sdk}.
 */
public final class JUnitUniqueIds {

    private JUnitUniqueIds() {}

    /** Engine id from {@code [engine:junit-jupiter]}, or empty. */
    public static String engineOf(@Nullable String id) {
        return segment(id, "engine");
    }

    /**
     * Binary class name: {@code [class:Outer]/[nested-class:Inner]} → {@code Outer$Inner}.
     * Empty when the id carries no {@code [class:…]} segment.
     */
    public static String classOf(@Nullable String id) {
        if (id == null) return "";
        String outer = segment(id, "class");
        if (outer.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(outer);
        // Multiple nested-class segments are rare; take all in order.
        int from = 0;
        while (true) {
            int i = id.indexOf("[nested-class:", from);
            if (i < 0) break;
            int start = i + "[nested-class:".length();
            int end = id.indexOf(']', start);
            if (end < 0) break;
            sb.append('$').append(percentDecode(id.substring(start, end).trim()));
            from = end + 1;
        }
        return sb.toString();
    }

    /**
     * Method label: a plain {@code [method:…]}, or a {@code @ParameterizedTest} /
     * {@code @TestTemplate} / {@code @TestFactory} template with its invocation path appended
     * ({@code m()[#1/#2]}). Empty when the id names no method-ish segment.
     */
    public static String methodOf(@Nullable String id) {
        if (id == null) return "";
        String method = segment(id, "method");
        if (!method.isEmpty()) return method;
        String template = segment(id, "test-template");
        if (template.isEmpty()) template = segment(id, "test-factory");
        if (template.isEmpty()) return "";
        String invocation = invocationPath(id);
        if (invocation.isEmpty()) return template;
        return template + "[" + invocation + "]";
    }

    /**
     * Every invocation-ish segment's value in id order, {@code /}-joined — nested dynamic
     * containers keep their indices, so sibling dynamic tests with the same leaf index
     * ({@code #1/#2} vs {@code #3/#2}) stay distinguishable.
     */
    private static String invocationPath(String id) {
        StringBuilder sb = new StringBuilder();
        int from = 0;
        while (true) {
            int open = id.indexOf('[', from);
            if (open < 0) break;
            int colon = id.indexOf(':', open);
            int close = id.indexOf(']', open);
            if (colon < 0 || close < 0 || colon > close) break;
            String type = id.substring(open + 1, colon).trim();
            switch (type) {
                case "test-template-invocation", "test-factory-invocation", "dynamic-container", "dynamic-test" -> {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(percentDecode(id.substring(colon + 1, close).trim()));
                }
                default -> {}
            }
            from = close + 1;
        }
        return sb.toString();
    }

    /** Value of the first {@code [key:value]} segment, percent-decoded, or empty. */
    public static String segment(@Nullable String id, String key) {
        if (id == null) return "";
        String needle = "[" + key + ":";
        int i = id.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = id.indexOf(']', start);
        if (end < 0) return "";
        return percentDecode(id.substring(start, end).trim());
    }

    /**
     * Decode {@code %XX} the way JUnit Platform writes unique-id strings
     * ({@code int[]} → {@code int%5B%5D}). Malformed escapes are left intact so a truncated id
     * still yields a usable label.
     */
    public static String percentDecode(@Nullable String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('%') < 0) return raw == null ? "" : raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = hexVal(raw.charAt(i + 1));
                int lo = hexVal(raw.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    out.append((char) ((hi << 4) | lo));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }
}
