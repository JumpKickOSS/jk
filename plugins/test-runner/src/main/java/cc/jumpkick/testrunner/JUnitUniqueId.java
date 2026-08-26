// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import java.util.Map;

/**
 * Parsed JUnit Platform {@code UniqueId} segments. Jupiter (and other engines) build the opaque
 * {@code [engine:…][class:…][method:…]} string; we only split it for the wire — we do not invent
 * identity. {@code UniqueId.parse} is the decoder of record; a malformed id falls back to the
 * shared string walk in {@link JUnitUniqueIds}, the same one the engine applies on its side of the
 * fork.
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
            // Malformed id — fall back to the shared bracket walk (still percent-decodes values).
        }
        return new JUnitUniqueId(
                uniqueId,
                JUnitUniqueIds.engineOf(uniqueId),
                JUnitUniqueIds.classOf(uniqueId),
                JUnitUniqueIds.methodOf(uniqueId));
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
        // Invocation-ish segments compose in order — nested @TestFactory containers each carry an
        // index, and dropping the container index makes sibling dynamic tests with the same leaf
        // index (#1/#2 vs #3/#2) label identically.
        StringBuilder invocation = new StringBuilder();
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
                case "test-template-invocation", "test-factory-invocation", "dynamic-container", "dynamic-test" -> {
                    if (invocation.length() > 0) invocation.append('/');
                    invocation.append(s.getValue());
                }
                default -> {}
            }
        }
        if (method.isEmpty()) {
            method = template;
            if (!method.isEmpty() && invocation.length() > 0) method = method + "[" + invocation + "]";
        }
        return new JUnitUniqueId(raw, engine, cls, method);
    }

    /** Put identity fields on a payload map (no display name — callers own presentation). */
    void putIdentity(Map<String, Object> payload) {
        if (!uniqueId.isEmpty()) payload.put("uniqueId", uniqueId);
        if (!testEngine.isEmpty()) payload.put("testEngine", testEngine);
        if (!testClass.isEmpty()) payload.put("testClass", testClass);
        if (!testMethod.isEmpty()) payload.put("testMethod", testMethod);
    }
}
