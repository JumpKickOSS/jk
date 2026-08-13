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
        String engine = segment(uniqueId, "engine");
        String cls = className(uniqueId);
        String method = methodName(uniqueId);
        return new JUnitUniqueId(uniqueId, engine, cls, method);
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
            sb.append('$').append(id, start, end);
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
        return id.substring(start, end).trim();
    }
}
