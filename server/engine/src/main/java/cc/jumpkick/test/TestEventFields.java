// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;

/**
 * The identity fields of one runner event, read the same way everywhere the aggregator looks at a
 * line: the split {@code testClass} / {@code testMethod} / {@code testEngine} fields when the runner
 * sent them, else what the unique id yields, else the legacy id or display name.
 */
final class TestEventFields {

    private TestEventFields() {}

    /** Prefer split fields; fall back to uniqueId / legacy id / display. */
    static String identityKey(String json) {
        String uid = Jsonl.str(json, "uniqueId");
        if (uid != null && !uid.isBlank()) return uid;
        String legacy = Jsonl.str(json, "id");
        return legacy == null ? "" : legacy;
    }

    /**
     * The {@code <testcase name>}: the runner's display name when it sent one — a parameterized
     * invocation's {@code [1] "build"}, a Spock feature — else the method label. That is what
     * every other JUnit XML writer records, so a report reader sees one convention.
     */
    static String xmlName(String json, String label) {
        String display = Jsonl.str(json, "display");
        return display != null && !display.isBlank() ? display : label;
    }

    static String progressLabel(String json) {
        String method = methodOf(json);
        if (!method.isEmpty()) return method;
        String cls = classNameOf(json);
        if (!cls.isEmpty()) {
            int dot = cls.lastIndexOf('.');
            return dot < 0 ? cls : cls.substring(dot + 1);
        }
        String display = Jsonl.str(json, "display");
        if (display != null && !display.isBlank()) return display;
        return identityKey(json);
    }

    static String classNameOf(String json) {
        String c = Jsonl.str(json, "testClass");
        if (c != null && !c.isBlank()) return c;
        return JUnitLauncher.classFromUniqueId(identityKey(json));
    }

    static String methodOf(String json) {
        String m = Jsonl.str(json, "testMethod");
        return m == null ? "" : m;
    }

    static String engineOf(String json) {
        String e = Jsonl.str(json, "testEngine");
        if (e != null && !e.isBlank()) return e;
        return JUnitLauncher.engineFromUniqueId(identityKey(json));
    }
}
