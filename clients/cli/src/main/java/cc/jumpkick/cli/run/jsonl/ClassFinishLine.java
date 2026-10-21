// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.wire.protocol.ClassFinishEvent;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import java.util.List;

/**
 * A test class finished under a task: its counts and wall, written as it completes, with the tier
 * the run executed and the class's methods at or over the slow floor.
 */
public record ClassFinishLine(
        long ts,
        String task,
        String module,
        String testClass,
        int tests,
        int failed,
        int skipped,
        long millis,
        String tier,
        List<TestClassResult.SlowMethod> slow) {

    public ClassFinishLine {
        slow = List.copyOf(slow);
    }

    public String encode() {
        JsonFields line = JsonlEnvelope.open(ts, EngineProtocol.CLASS_FINISH)
                .string("task", task)
                .string("module", module)
                .string(EngineProtocol.TEST_CLASS_FIELD, testClass)
                .number("tests", tests)
                .number("failed", failed)
                .number("skipped", skipped)
                .number("millis", millis)
                .optionalNonBlankString("tier", tier);
        if (!slow.isEmpty()) line.token(ClassFinishEvent.SLOW, ClassFinishEvent.slowArray(slow));
        return line.finish();
    }

    public static ClassFinishLine decode(String json) {
        return new ClassFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "module"),
                Jsonl.requiredStr(json, EngineProtocol.TEST_CLASS_FIELD),
                Jsonl.intValue(json, "tests", 0),
                Jsonl.intValue(json, "failed", 0),
                Jsonl.intValue(json, "skipped", 0),
                Jsonl.longValue(json, "millis", 0),
                ClassFinishEvent.tier(json),
                ClassFinishEvent.slow(json));
    }
}
