// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

/**
 * Parsed JUnit Platform {@code UniqueId} segments. Jupiter (and other engines) build the opaque
 * {@code [engine:…][class:…][method:…]} string; we only split it for the wire — we do not invent
 * identity.
 */
final class JUnitUniqueId {

    final String uniqueId;
    final String testEngine;
    final String testClass;
    final String testMethod;

    private JUnitUniqueId(String uniqueId, String testEngine, String testClass, String testMethod) {
        this.uniqueId = uniqueId == null ? "" : uniqueId;
        this.testEngine = testEngine == null ? "" : testEngine;
        this.testClass = testClass == null ? "" : testClass;
        this.testMethod = testMethod == null ? "" : testMethod;
    }

    static JUnitUniqueId parse(String uniqueId) {
        if (uniqueId == null || uniqueId.isBlank()) {
            return new JUnitUniqueId("", "", "", "");
        }
        try {
            return fromPlatform(org.junit.platform.engine.UniqueId.parse(uniqueId), uniqueId);
        } catch (RuntimeException ignored) {
            // Malformed id — fall back to a bracket walk that still percent-decodes values.
        }
        String engine = segment(uniqueId, "engine");
        String cls = className(uniqueId);
        String method = methodName(uniqueId);
        return new JUnitUniqueId(uniqueId, engine, cls, method);
    }

    /**
     * JUnit Platform percent-encodes {@code [ ] / %} in the unique-id string
     * ({@code int[]} → {@code int%5B%5D}). {@link org.junit.platform.engine.UniqueId#parse}
     * is the decoder of record.
     */
    private static JUnitUniqueId fromPlatform(org.junit.platform.engine.UniqueId uid, String raw) {
        String engine = "";
        String cls = "";
        String method = "";
        String template = "";
        String invocation = "";
        for (org.junit.platform.engine.UniqueId.Segment s : uid.getSegments()) {
            switch (s.getType()) {
                case "engine" -> engine = s.getValue();
                case "class" -> cls = s.getValue();
                case "nested-class" -> cls = cls.isEmpty() ? s.getValue() : cls + "$" + s.getValue();
                case "method" -> method = s.getValue();
                case "test-template" -> template = s.getValue();
                case "test-factory" -> {
                    if (template.isEmpty()) template = s.getValue();
                }
                case "test-template-invocation", "test-factory-invocation", "dynamic-test" -> invocation = s.getValue();
                default -> {}
            }
        }
        if (method.isEmpty()) {
            method = template;
            if (!method.isEmpty() && !invocation.isEmpty()) method = method + "[" + invocation + "]";
        }
        return new JUnitUniqueId(raw, engine, cls, method);
    }

    /** Put identity fields on a payload map (no display name — callers own presentation). */
    void putIdentity(java.util.Map<String, Object> payload) {
        if (!uniqueId.isEmpty()) payload.put("uniqueId", uniqueId);
        if (!testEngine.isEmpty()) payload.put("testEngine", testEngine);
        if (!testClass.isEmpty()) payload.put("testClass", testClass);
        if (!testMethod.isEmpty()) payload.put("testMethod", testMethod);
    }

    private static String className(String id) {
        String outer = segment(id, "class");
        if (outer.isEmpty()) return "";
        // Nested: [class:Outer]/[nested-class:Inner] → Outer$Inner (Java binary name).
        String nested = segment(id, "nested-class");
        if (nested.isEmpty()) return outer;
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

    private static String methodName(String id) {
        String method = segment(id, "method");
        if (!method.isEmpty()) return method;
        // @ParameterizedTest / @TestTemplate / @RepeatedTest
        String template = segment(id, "test-template");
        if (template.isEmpty()) template = segment(id, "test-factory");
        if (template.isEmpty()) return "";
        String invocation = segment(id, "test-template-invocation");
        if (invocation.isEmpty()) invocation = segment(id, "test-factory-invocation");
        if (invocation.isEmpty()) invocation = segment(id, "dynamic-test");
        if (invocation.isEmpty()) return template;
        return template + "[" + invocation + "]";
    }

    /** Value of the first {@code [key:value]} segment, or empty. */
    static String segment(String id, String key) {
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
     * Decode {@code %XX} the way JUnit Platform writes unique-id strings. Malformed escapes are
     * left intact so a truncated id still yields a usable label.
     */
    static String percentDecode(String raw) {
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
