// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestClassResult;
import java.util.ArrayList;
import java.util.List;

/** A test class finished under a task (see {@link EngineProtocol#CLASS_FINISH}). */
public record ClassFinishEvent(String dir, String task, TestClassResult result) {
    public String encode() {
        RequestJson line = RequestJson.event(EngineProtocol.CLASS_FINISH)
                .string("dir", dir)
                .string("task", task)
                .string("module", result.module())
                .string(EngineProtocol.TEST_CLASS_FIELD, result.className())
                .number("tests", result.tests())
                .number("failed", result.failed())
                .number("skipped", result.skipped())
                .number("millis", result.durationMs())
                .optionalNonBlankString("tier", result.tier());
        if (!result.slow().isEmpty()) line.token(SLOW, slowArray(result.slow()));
        return line.finish();
    }

    public static ClassFinishEvent decode(String json) {
        return new ClassFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                new TestClassResult(
                        Jsonl.requiredStr(json, "module"),
                        Jsonl.requiredStr(json, EngineProtocol.TEST_CLASS_FIELD),
                        Jsonl.intValue(json, "tests", 0),
                        Jsonl.intValue(json, "failed", 0),
                        Jsonl.intValue(json, "skipped", 0),
                        Jsonl.longValue(json, "millis", 0),
                        tier(json),
                        slow(json)));
    }

    /**
     * The field holding a class's slow methods, {@code [{"method":…,"millis":…}]}, written only
     * when it has some. It goes last: the line's own {@code millis} must be the first a key scan finds.
     */
    public static final String SLOW = "slow";

    /** {@code slow} as its wire array. */
    public static String slowArray(List<TestClassResult.SlowMethod> slow) {
        StringBuilder array = new StringBuilder("[");
        for (TestClassResult.SlowMethod m : slow) {
            if (array.length() > 1) array.append(',');
            array.append(JsonFields.object()
                    .string("method", m.name())
                    .number("millis", m.millis())
                    .finish());
        }
        return array.append(']').toString();
    }

    /** The {@code slow} methods of a class-finish line; empty when it has none. */
    public static List<TestClassResult.SlowMethod> slow(String json) {
        List<TestClassResult.SlowMethod> out = new ArrayList<>();
        for (String m : Jsonl.objectArray(json, SLOW)) {
            out.add(new TestClassResult.SlowMethod(Jsonl.requiredStr(m, "method"), Jsonl.longValue(m, "millis", 0)));
        }
        return out;
    }

    /** The {@code tier} of a class-finish line; empty when it names none. */
    public static String tier(String json) {
        String tier = Jsonl.topStr(json, "tier");
        return tier == null ? "" : tier;
    }
}
