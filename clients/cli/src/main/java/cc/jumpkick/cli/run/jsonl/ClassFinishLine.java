// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A test class finished under a task: its counts and wall, written as it completes. */
public record ClassFinishLine(
        long ts, String task, String module, String testClass, int tests, int failed, int skipped, long millis) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.CLASS_FINISH)
                .string("task", task)
                .string("module", module)
                .string(EngineProtocol.TEST_CLASS_FIELD, testClass)
                .number("tests", tests)
                .number("failed", failed)
                .number("skipped", skipped)
                .number("millis", millis)
                .finish();
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
                Jsonl.longValue(json, "millis", 0));
    }
}
